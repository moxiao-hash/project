package com.moxiao.studypilot.agent.developer;

import com.moxiao.studypilot.agent.runner.RunnerTemplateType;
import com.moxiao.studypilot.roadmap.infrastructure.ProjectWorkspaceEntity;
import com.moxiao.studypilot.roadmap.infrastructure.ProjectWorkspaceJpaRepository;
import com.moxiao.studypilot.shared.error.ConflictException;
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
