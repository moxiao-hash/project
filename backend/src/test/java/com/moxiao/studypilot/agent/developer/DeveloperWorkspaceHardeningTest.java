package com.moxiao.studypilot.agent.developer;

import com.moxiao.studypilot.agent.runner.RunnerTemplateType;
import com.moxiao.studypilot.roadmap.infrastructure.ProjectWorkspaceEntity;
import com.moxiao.studypilot.roadmap.infrastructure.ProjectWorkspaceJpaRepository;
import com.moxiao.studypilot.shared.error.ConflictException;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Developer Agent 的工作区读取、测试推荐与 Git 治理必须在执行前就拒绝越界与泄漏。
 */
@SpringBootTest
class DeveloperWorkspaceHardeningTest {

    private static final String CREDENTIAL_NAME = "api_key=abcdef1234567890.txt";

    @Autowired private WorkspaceDeveloperService service;
    @Autowired private ProjectWorkspaceJpaRepository workspaceRepository;

    @TempDir Path temporary;

    private Path root;
    private Path remote;
    private String owner;
    private String other;
    private String workspaceId;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectory(temporary.resolve("workspace"));
        remote = temporary.resolve("remote.git");
        Files.createDirectories(root.resolve("backend"));
        Files.createDirectories(root.resolve("web"));
        Files.createDirectories(root.resolve("ai-service"));
        Files.writeString(root.resolve("backend/pom.xml"), "<project/>");
        Files.writeString(root.resolve("web/package.json"), "{}");
        Files.writeString(root.resolve("ai-service/pyproject.toml"), "[project]");
        Files.writeString(root.resolve("README.md"), "initial\n");
        owner = "hardening-" + System.nanoTime();
        other = "hardening-other-" + System.nanoTime();
        run(root, "git", "init", "-b", "main");
        run(root, "git", "config", "user.name", "StudyPilot");
        run(root, "git", "config", "user.email", "test@example.com");
        run(root, "git", "init", "--bare", remote.toString());
        run(root, "git", "remote", "add", "origin", remote.toUri().toString());
        run(root, "git", "add", "README.md");
        run(root, "git", "commit", "-m", "initial");
        run(root, "git", "push", "-u", "origin", "main");
        workspaceId = register(owner, root);
    }

    @Test
    void recommendTestsCarriesValidatedRelativeWorkingDirectories() {
        DeveloperTestRecommendation recommendation = service.recommendTests(owner, workspaceId,
                List.of("backend/src/Main.java", "web/src/App.vue", "ai-service/app/main.py"));

        assertThat(recommendation.executions()).extracting(
                        DeveloperTestRecommendation.RecommendedExecution::templateType,
                        DeveloperTestRecommendation.RecommendedExecution::workingDirectory)
                .containsExactlyInAnyOrder(
                        tuple(RunnerTemplateType.MAVEN_TEST, "backend"),
                        tuple(RunnerTemplateType.NPM_TEST, "web"),
                        tuple(RunnerTemplateType.PYTEST, "ai-service"));
    }

    @Test
    void recommendTestsNeverPointsAtASymlinkedProjectDirectory() throws Exception {
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.writeString(outside.resolve("pom.xml"), "<project/>");
        Files.delete(root.resolve("backend/pom.xml"));
        Files.delete(root.resolve("backend"));
        Files.createSymbolicLink(root.resolve("backend"), outside);

        DeveloperTestRecommendation recommendation = service.recommendTests(
                owner, workspaceId, List.of("backend/src/Main.java"));

        assertThat(recommendation.executions()).isEmpty();
    }

    @Test
    void treeAndGitStatusNeverExposeSensitiveNames() throws Exception {
        Files.writeString(root.resolve(CREDENTIAL_NAME), "harmless\n");

        WorkspaceFileTreeResponse tree = service.getFileTree(owner, workspaceId);
        WorkspaceGitStatusResponse status = service.getGitStatus(owner, workspaceId);

        assertThat(tree.entries())
                .noneMatch(entry -> entry.relativePath().contains("abcdef1234567890"));
        assertThat(status.untrackedFiles())
                .noneMatch(path -> path.contains("abcdef1234567890"));
    }

    @Test
    void readFileAppliesADeterministicLineBound() throws Exception {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < 5_000; index++) {
            builder.append("line ").append(index).append('\n');
        }
        Files.writeString(root.resolve("big.txt"), builder.toString());

        WorkspaceFileReadResponse response = service.readFile(owner, workspaceId, "big.txt");

        assertThat(response.truncated()).isTrue();
        assertThat(response.content().lines().count()).isLessThanOrEqualTo(2_000);
    }

    @Test
    void readFileRejectsHostileAndEscapingPathsBeforeAnyContentIsReturned() throws Exception {
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "outside-secret\n");
        Files.createSymbolicLink(root.resolve("linked"), outside);
        Files.writeString(root.resolve("safe.txt"), "safe\n");

        // 绝对路径、目录穿越、穿越父目录的符号链接都必须失败关闭，且不得返回任何内容。
        for (String hostile : List.of(
                "/etc/hosts", "../outside/secret.txt", "linked/secret.txt",
                "safe.txt/../../outside/secret.txt", "..", "")) {
            assertThatThrownBy(() -> service.readFile(owner, workspaceId, hostile))
                    .as("读取路径 %s 必须在返回内容之前被拒绝", hostile)
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void readFileRejectsBinaryContent() throws Exception {
        byte[] binary = new byte[64];
        java.util.Arrays.fill(binary, (byte) 'a');
        binary[16] = 0;
        Files.write(root.resolve("blob.txt"), binary);

        assertThatThrownBy(() -> service.readFile(owner, workspaceId, "blob.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("二进制");
    }

    @Test
    void readFileRejectsFilesBeyondTheDeterministicSizeBound() throws Exception {
        byte[] oversized = new byte[64 * 1024 + 1];
        java.util.Arrays.fill(oversized, (byte) 'a');
        Files.write(root.resolve("oversized.txt"), oversized);

        // 超限文件必须先按大小拒绝，而不是先读入再裁剪。
        assertThatThrownBy(() -> service.readFile(owner, workspaceId, "oversized.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("上限");
    }

    @Test
    void pushPreviewBindsRemoteUrlRemoteRefAndFiniteTimeout() throws Exception {
        commitLocalChange();

        GitPushPreview preview = service.previewGitPush(owner, workspaceId);

        assertThat(preview.remoteName()).isEqualTo("origin");
        assertThat(preview.remoteUrlDigest()).hasSize(64);
        assertThat(preview.expectedRemoteRef()).isEqualTo("refs/remotes/origin/main");
        // 只绑定 ref 名称是常量，必须绑定预览时解析出的远端提交。
        assertThat(preview.expectedRemoteRefCommit()).matches("[0-9a-f]{40}");
        assertThat(preview.timeoutSeconds()).isBetween(1, 300);
        assertThat(preview.aheadCount()).isEqualTo(1);
    }

    @Test
    void pushConfirmationFailsClosedWhenTheRemoteUrlChanges() throws Exception {
        commitLocalChange();
        GitPushPreview preview = service.previewGitPush(owner, workspaceId);
        String remoteBefore = remoteHead();
        run(root, "git", "remote", "set-url", "origin",
                temporary.resolve("other.git").toUri().toString());

        assertThatThrownBy(() -> service.pushConfirmed(owner, pushRequest(preview)))
                .isInstanceOf(ConflictException.class);
        assertThat(remoteHead()).isEqualTo(remoteBefore);
    }

    @Test
    void pushConfirmationFailsClosedWhenTheRemoteRefAdvances() throws Exception {
        commitLocalChange();
        // 再多一个本地提交，保证远端跟踪 ref 前移后仍然"有内容可推"，
        // 从而只验证远端 ref 漂移本身，而不是被 aheadCount 检查顺带拦下。
        Files.writeString(root.resolve("README.md"), "third\n");
        run(root, "git", "add", "README.md");
        run(root, "git", "commit", "-m", "third");
        GitPushPreview preview = service.previewGitPush(owner, workspaceId);
        String remoteBefore = remoteHead();
        assertThat(preview.aheadCount()).isEqualTo(2);
        // 远端跟踪 ref 前移到另一个提交：预览绑定的 commit 已失效。
        run(root, "git", "update-ref", "refs/remotes/origin/main", "HEAD~1");
        assertThat(service.previewGitPush(owner, workspaceId).expectedRemoteRefCommit())
                .isNotEqualTo(preview.expectedRemoteRefCommit());
        assertThat(service.previewGitPush(owner, workspaceId).aheadCount()).isEqualTo(1);

        assertThatThrownBy(() -> service.pushConfirmed(owner, pushRequest(preview)))
                .isInstanceOf(ConflictException.class);
        assertThat(remoteHead()).isEqualTo(remoteBefore);
    }

    @Test
    void pushPreviewBindsTheEffectivePushUrlInsteadOfTheFetchUrl() throws Exception {
        Path pushRemote = createBareRemote("push-target.git");
        String fetchOnlyDigest = service.previewGitPush(owner, workspaceId).remoteUrlDigest();
        commitLocalChange();
        // remote.origin.url 指向 fetch 远端，remote.origin.pushurl 指向另一个 bare remote：
        // JGit 的 PUSH 使用 pushurl，因此预览必须绑定 pushurl 指向的目标。
        run(root, "git", "config", "remote.origin.pushurl", pushRemote.toUri().toString());
        GitPushPreview preview = service.previewGitPush(owner, workspaceId);

        assertThat(preview.remoteUrlDigest()).hasSize(64).isNotEqualTo(fetchOnlyDigest);
        String originBefore = remoteHead();
        service.pushConfirmed(owner, pushRequest(preview));
        assertThat(bareHead(pushRemote)).isEqualTo(preview.expectedHead());
        assertThat(remoteHead()).isEqualTo(originBefore);
    }

    @Test
    void pushConfirmationFailsClosedWhenThePushUrlChangesAfterPreview() throws Exception {
        Path first = createBareRemote("pushurl-first.git");
        Path second = createBareRemote("pushurl-second.git");
        run(root, "git", "push", first.toUri().toString(), "main");
        run(root, "git", "push", second.toUri().toString(), "main");
        run(root, "git", "config", "remote.origin.pushurl", first.toUri().toString());
        commitLocalChange();
        GitPushPreview preview = service.previewGitPush(owner, workspaceId);
        String firstBefore = bareHead(first);
        String secondBefore = bareHead(second);
        run(root, "git", "config", "remote.origin.pushurl", second.toUri().toString());

        assertThatThrownBy(() -> service.pushConfirmed(owner, pushRequest(preview)))
                .isInstanceOf(ConflictException.class);
        assertThat(bareHead(first)).isEqualTo(firstBefore);
        assertThat(bareHead(second)).isEqualTo(secondBefore);
    }

    @Test
    void pushRejectsMultipleEffectiveDestinationsInsteadOfPartiallyPushing() throws Exception {
        Path first = createBareRemote("multi-first.git");
        Path second = createBareRemote("multi-second.git");
        run(root, "git", "push", first.toUri().toString(), "main");
        run(root, "git", "push", second.toUri().toString(), "main");
        String firstBefore = bareHead(first);
        String secondBefore = bareHead(second);
        commitLocalChange();

        // 两个 pushurl：目标不唯一，预览直接拒绝，不得部分推送。
        run(root, "git", "config", "--add", "remote.origin.pushurl", first.toUri().toString());
        run(root, "git", "config", "--add", "remote.origin.pushurl", second.toUri().toString());
        assertThatThrownBy(() -> service.previewGitPush(owner, workspaceId))
                .isInstanceOf(RuntimeException.class);

        // 单目标预览后追加第二个目标：确认同样失败关闭，两个远端都不得变化。
        run(root, "git", "config", "--unset-all", "remote.origin.pushurl");
        run(root, "git", "config", "remote.origin.pushurl", first.toUri().toString());
        GitPushPreview single = service.previewGitPush(owner, workspaceId);
        run(root, "git", "config", "--add", "remote.origin.pushurl", second.toUri().toString());
        assertThatThrownBy(() -> service.pushConfirmed(owner, pushRequest(single)))
                .isInstanceOf(ConflictException.class);
        assertThat(bareHead(first)).isEqualTo(firstBefore);
        assertThat(bareHead(second)).isEqualTo(secondBefore);

        // 没有 pushurl 但有多个 fetch URL 时，目标同样不唯一。
        run(root, "git", "config", "--unset-all", "remote.origin.pushurl");
        run(root, "git", "config", "--add", "remote.origin.url", second.toUri().toString());
        assertThatThrownBy(() -> service.previewGitPush(owner, workspaceId))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void pushDestinationDigestDistinguishesTheRemoteUser() throws Exception {
        // 语法合法的 SSH URI，仅用户名不同：可能解析到不同账号/仓库，必须产生不同摘要。
        run(root, "git", "config", "remote.origin.pushurl", "ssh://alice@127.0.0.1:1/team/repo.git");
        String sshAlice = service.previewGitPush(owner, workspaceId).remoteUrlDigest();
        run(root, "git", "config", "remote.origin.pushurl", "ssh://bob@127.0.0.1:1/team/repo.git");
        String sshBob = service.previewGitPush(owner, workspaceId).remoteUrlDigest();
        assertThat(sshAlice).isNotEqualTo(sshBob);

        // SCP 形态同样必须区分用户。
        run(root, "git", "config", "remote.origin.pushurl", "alice@127.0.0.1:team/repo.git");
        String scpAlice = service.previewGitPush(owner, workspaceId).remoteUrlDigest();
        run(root, "git", "config", "remote.origin.pushurl", "bob@127.0.0.1:team/repo.git");
        String scpBob = service.previewGitPush(owner, workspaceId).remoteUrlDigest();
        assertThat(scpAlice).isNotEqualTo(scpBob);
        assertThat(scpAlice).isNotEqualTo(sshAlice);

        // 等价的显式默认端口必须保持同一目标，避免误报漂移。
        run(root, "git", "config", "remote.origin.pushurl", "ssh://alice@127.0.0.1/team/repo.git");
        String implicitPort = service.previewGitPush(owner, workspaceId).remoteUrlDigest();
        run(root, "git", "config", "remote.origin.pushurl", "ssh://alice@127.0.0.1:22/team/repo.git");
        String explicitPort = service.previewGitPush(owner, workspaceId).remoteUrlDigest();
        assertThat(implicitPort).isEqualTo(explicitPort);
    }

    @Test
    void pushConfirmationFailsClosedWhenTheRemoteUserChangesAfterPreview() throws Exception {
        run(root, "git", "config", "remote.origin.pushurl", "ssh://alice@127.0.0.1:1/team/repo.git");
        commitLocalChange();
        GitPushPreview preview = service.previewGitPush(owner, workspaceId);
        run(root, "git", "config", "remote.origin.pushurl", "ssh://bob@127.0.0.1:1/team/repo.git");

        // 必须在任何网络或推送尝试之前失败关闭：若摘要忽略用户名，这里会去连接 127.0.0.1:1
        // 并抛出传输异常，而不是 ConflictException。
        assertThatThrownBy(() -> service.pushConfirmed(owner, pushRequest(preview)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void pushRejectsRemoteOptionsThatACapturedDestinationCannotCarry() throws Exception {
        commitLocalChange();

        // 预览阶段：JGit 只在按远端名解析时通过 applyConfig 应用 receivepack；
        // 捕获目标后无法保留该设置，必须在产生任何副作用之前拒绝而不是静默丢弃。
        run(root, "git", "config", "remote.origin.receivepack", "/opt/custom/git-receive-pack");
        assertThatThrownBy(() -> service.previewGitPush(owner, workspaceId))
                .isInstanceOf(IllegalArgumentException.class);

        // 确认阶段：预览之后才新增 receivepack，同样必须失败关闭。
        run(root, "git", "config", "--unset", "remote.origin.receivepack");
        GitPushPreview valid = service.previewGitPush(owner, workspaceId);
        run(root, "git", "config", "remote.origin.receivepack", "/opt/custom/git-receive-pack");
        assertThatThrownBy(() -> service.pushConfirmed(owner, pushRequest(valid)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void capturedPushBindingCannotBeRedirectedByLaterRemoteConfigMutation() throws Exception {
        Path bound = createBareRemote("bound.git");
        Path substituted = createBareRemote("substituted.git");
        run(root, "git", "push", bound.toUri().toString(), "main");
        run(root, "git", "push", substituted.toUri().toString(), "main");
        run(root, "git", "config", "remote.origin.pushurl", bound.toUri().toString());
        commitLocalChange();
        String boundBefore = bareHead(bound);
        String substitutedBefore = bareHead(substituted);

        // 确定性捕获目标（不依赖任何时序）：捕获之后再改写远端配置。
        GitPushDestination captured;
        try (Git git = Git.open(root.toFile())) {
            captured = service.capturePushDestination(git.getRepository());
        }
        run(root, "git", "config", "remote.origin.pushurl", substituted.toUri().toString());
        assertThat(service.previewGitPush(owner, workspaceId).remoteUrlDigest())
                .isNotEqualTo(captured.digest());

        GitPushRequest request = new GitPushRequest(workspaceId, "origin", "main",
                captured.digest(), captured.digest(), "refs/remotes/origin/main", "", 30);
        try (Git git = Git.open(root.toFile())) {
            service.pushToCapturedDestination(git, captured, request);
        }

        // 被捕获的目标前进，被替换的远端必须保持原样。
        assertThat(bareHead(bound)).isNotEqualTo(boundBefore);
        assertThat(bareHead(substituted)).isEqualTo(substitutedBefore);
    }

    @Test
    void pushConfirmationFailsClosedWhenTheTimeoutIsOutOfRange() throws Exception {
        commitLocalChange();
        GitPushPreview preview = service.previewGitPush(owner, workspaceId);

        assertThatThrownBy(() -> service.pushConfirmed(owner, new GitPushRequest(
                workspaceId, preview.remoteName(), preview.branch(), preview.expectedHead(),
                preview.remoteUrlDigest(), preview.expectedRemoteRef(),
                preview.expectedRemoteRefCommit(), 0)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void anotherOwnerCannotReadOrPatchOrPushTheWorkspace() {
        assertThatThrownBy(() -> service.readFile(other, workspaceId, "README.md"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.getFileTree(other, workspaceId))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.previewGitPush(other, workspaceId))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.previewGitCommit(other, workspaceId, List.of("README.md"), "msg"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.previewPatch(other, workspaceId, "README.md", "@@ -1 +1 @@\n-a\n+b\n"))
                .isInstanceOf(RuntimeException.class);
    }

    private void commitLocalChange() throws Exception {
        Files.writeString(root.resolve("README.md"), "changed\n");
        run(root, "git", "add", "README.md");
        run(root, "git", "commit", "-m", "local change");
    }

    private GitPushRequest pushRequest(GitPushPreview preview) {
        return new GitPushRequest(workspaceId, preview.remoteName(), preview.branch(),
                preview.expectedHead(), preview.remoteUrlDigest(), preview.expectedRemoteRef(),
                preview.expectedRemoteRefCommit(), preview.timeoutSeconds());
    }

    /** 新建一个独立的 bare remote；每个目标各自持有 refs/heads/main 便于断言"没有被写入"。 */
    private Path createBareRemote(String name) throws Exception {
        Path directory = Files.createDirectory(temporary.resolve(name + "-dir"));
        Path bare = directory.resolve(name);
        run(root, "git", "init", "--bare", bare.toString());
        return bare;
    }

    private String bareHead(Path bare) throws Exception {
        return run(root, "git", "--git-dir", bare.toString(), "rev-parse", "refs/heads/main").trim();
    }

    private String remoteHead() throws Exception {
        return run(root, "git", "--git-dir", remote.toString(), "rev-parse", "refs/heads/main").trim();
    }

    private String register(String ownerId, Path workspaceRoot) throws Exception {
        String id = UUID.randomUUID().toString();
        workspaceRepository.saveAndFlush(new ProjectWorkspaceEntity(
                id, ownerId, "hardening", workspaceRoot.toRealPath().toString(),
                "test-hash", Instant.now()));
        return id;
    }

    private static String run(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) throw new IllegalStateException(String.join(" ", command) + ": " + output);
        return output;
    }
}
