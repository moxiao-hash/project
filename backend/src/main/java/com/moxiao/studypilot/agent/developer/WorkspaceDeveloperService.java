package com.moxiao.studypilot.agent.developer;

import com.moxiao.studypilot.roadmap.infrastructure.ProjectWorkspaceEntity;
import com.moxiao.studypilot.roadmap.infrastructure.ProjectWorkspaceJpaRepository;
import com.moxiao.studypilot.shared.error.ConflictException;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;

@Service
public class WorkspaceDeveloperService {
    private static final int MAX_TREE_ENTRIES = 2_000;
    private static final int MAX_READ_BYTES = 64 * 1024;
    private static final int MAX_READ_LINES = 2_000;
    private static final int MAX_SEARCH_FILES = 2_000;
    private static final int MAX_SEARCH_MATCHES = 50;
    private static final int MAX_GIT_OUTPUT_BYTES = 64 * 1024;
    private static final int MAX_PATCH_CHARACTERS = 20_000;
    private static final int PUSH_TIMEOUT_SECONDS = 30;
    private static final int MAX_PUSH_TIMEOUT_SECONDS = 120;
    private static final int FILE_LOCK_STRIPES = 64;
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            ".git", ".idea", ".ssh", ".aws", ".venv", "venv", "node_modules",
            "target", "dist", "build", "out", "__pycache__");
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".java", ".kt", ".py", ".js", ".ts", ".vue", ".html", ".css",
            ".scss", ".sql", ".xml", ".md", ".txt", ".json", ".yaml", ".yml");

    private final ProjectWorkspaceJpaRepository workspaceRepository;
    private final UnifiedDiffApplier diffApplier = new UnifiedDiffApplier();
    private final Object[] fileLocks = createFileLocks();

    public WorkspaceDeveloperService(ProjectWorkspaceJpaRepository workspaceRepository) {
        this.workspaceRepository = workspaceRepository;
    }

    public WorkspaceFileTreeResponse getFileTree(String ownerId, String workspaceId) {
        Path root = workspaceRoot(findWorkspace(ownerId, workspaceId));
        List<WorkspaceFileTreeResponse.FileEntry> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root, 6)) {
            List<Path> candidates = stream.filter(path -> !path.equals(root))
                    .sorted(Comparator.comparing(Path::toString)).toList();
            for (Path candidate : candidates) {
                if (entries.size() >= MAX_TREE_ENTRIES) break;
                Path relative = root.relativize(candidate);
                if (excluded(relative) || Files.isSymbolicLink(candidate)) continue;
                boolean directory = Files.isDirectory(candidate);
                if (!directory && (!Files.isRegularFile(candidate) || sensitive(relative))) continue;
                entries.add(new WorkspaceFileTreeResponse.FileEntry(
                        DeveloperOutputSanitizer.sanitize(portable(relative)),
                        DeveloperOutputSanitizer.sanitize(candidate.getFileName().toString()),
                        directory, directory ? 0 : Files.size(candidate)));
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("读取工作区文件树失败", exception);
        }
        return new WorkspaceFileTreeResponse(workspaceId, null, List.copyOf(entries));
    }

    public WorkspaceFileReadResponse readFile(String ownerId, String workspaceId, String relativePath) {
        Path root = workspaceRoot(findWorkspace(ownerId, workspaceId));
        Path target = resolveReadableFile(root, relativePath);
        try {
            long size = Files.size(target);
            if (size > MAX_READ_BYTES) throw new IllegalArgumentException("文件超过读取上限");
            byte[] bytes;
            try (InputStream input = Files.newInputStream(target)) {
                bytes = input.readNBytes(MAX_READ_BYTES + 1);
            }
            if (containsNul(bytes)) throw new IllegalArgumentException("不允许读取二进制文件");
            String content = new String(bytes, StandardCharsets.UTF_8);
            String[] lines = content.split("\n", -1);
            boolean truncated = lines.length > MAX_READ_LINES;
            if (truncated) {
                content = String.join("\n", java.util.Arrays.copyOf(lines, MAX_READ_LINES));
            }
            return new WorkspaceFileReadResponse(workspaceId, portable(root.relativize(target)),
                    DeveloperOutputSanitizer.sanitize(content), size, truncated);
        } catch (IOException exception) {
            throw new IllegalArgumentException("读取文件失败", exception);
        }
    }

    public WorkspaceSearchResponse searchCode(String ownerId, String workspaceId, String query) {
        String needle = query == null ? "" : query.trim();
        if (needle.isEmpty() || needle.length() > 200) {
            throw new IllegalArgumentException("搜索关键词长度必须为 1 到 200 字符");
        }
        Path root = workspaceRoot(findWorkspace(ownerId, workspaceId));
        List<WorkspaceSearchResponse.MatchEntry> matches = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root, 6)) {
            List<Path> candidates = stream.filter(Files::isRegularFile)
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> !excluded(root.relativize(path)))
                    .filter(path -> !sensitive(root.relativize(path)))
                    .sorted(Comparator.comparing(Path::toString))
                    .limit(MAX_SEARCH_FILES).toList();
            for (Path file : candidates) {
                if (matches.size() >= MAX_SEARCH_MATCHES) break;
                if (Files.size(file) > MAX_READ_BYTES) continue;
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int index = 0; index < lines.size() && matches.size() < MAX_SEARCH_MATCHES; index++) {
                    if (lines.get(index).contains(needle)) {
                        matches.add(new WorkspaceSearchResponse.MatchEntry(
                                portable(root.relativize(file)), index + 1,
                                DeveloperOutputSanitizer.sanitize(lines.get(index).trim())));
                    }
                }
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("搜索代码失败", exception);
        }
        return new WorkspaceSearchResponse(workspaceId, needle, List.copyOf(matches));
    }

    public WorkspaceGitStatusResponse getGitStatus(String ownerId, String workspaceId) {
        Path root = workspaceRoot(findWorkspace(ownerId, workspaceId));
        if (!Files.exists(root.resolve(".git"))) {
            return new WorkspaceGitStatusResponse(workspaceId, false, null, null, true, List.of(), List.of());
        }
        try (Git git = openGit(root)) {
            var status = git.status().call();
            Set<String> modified = new HashSet<>();
            modified.addAll(status.getAdded());
            modified.addAll(status.getChanged());
            modified.addAll(status.getModified());
            modified.addAll(status.getMissing());
            modified.addAll(status.getRemoved());
            modified.addAll(status.getConflicting());
            Repository repository = git.getRepository();
            ObjectId head = repository.resolve(Constants.HEAD);
            return new WorkspaceGitStatusResponse(workspaceId, true, repository.getBranch(),
                    head == null ? null : head.name(), status.isClean(),
                    sanitizedSorted(modified), sanitizedSorted(status.getUntracked()));
        } catch (Exception exception) {
            throw new IllegalArgumentException("读取 Git 状态失败", exception);
        }
    }

    public WorkspaceGitTextResponse getGitDiff(String ownerId, String workspaceId) {
        Path root = workspaceRoot(findWorkspace(ownerId, workspaceId));
        try (Git git = openGit(root); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Repository repository = git.getRepository();
            ObjectId head = repository.resolve(Constants.HEAD + "^{tree}");
            if (head == null) return new WorkspaceGitTextResponse(workspaceId, "", false);
            try (ObjectReader reader = repository.newObjectReader();
                 DiffFormatter formatter = new DiffFormatter(output)) {
                CanonicalTreeParser oldTree = new CanonicalTreeParser();
                oldTree.reset(reader, head);
                formatter.setRepository(repository);
                formatter.format(oldTree, new FileTreeIterator(repository));
            }
            return clippedGitText(workspaceId, output.toByteArray());
        } catch (Exception exception) {
            throw new IllegalArgumentException("读取 Git 差异失败", exception);
        }
    }

    public WorkspaceGitTextResponse getGitLog(String ownerId, String workspaceId, int requestedLimit) {
        Path root = workspaceRoot(findWorkspace(ownerId, workspaceId));
        int limit = Math.max(1, Math.min(requestedLimit, 50));
        try (Git git = openGit(root)) {
            StringBuilder result = new StringBuilder();
            for (var commit : git.log().setMaxCount(limit).call()) {
                result.append(commit.getId().name()).append(' ')
                        .append(commit.getShortMessage()).append('\n');
            }
            return clippedGitText(workspaceId, result.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalArgumentException("读取 Git 日志失败", exception);
        }
    }

    public DeveloperTestRecommendation recommendTests(
            String ownerId, String workspaceId, List<String> changedFiles
    ) {
        Path root = workspaceRoot(findWorkspace(ownerId, workspaceId));
        List<String> changes = changedFiles == null ? List.of() : changedFiles;
        List<DeveloperTestRecommendation.RecommendedExecution> executions = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        if (matchesArea(changes, "backend/", ".java", ".xml")
                && projectDirectory(root, "backend", "pom.xml")) {
            executions.add(execution(com.moxiao.studypilot.agent.runner.RunnerTemplateType.MAVEN_TEST,
                    "backend"));
            reasons.add("Java/Maven 代码发生变化");
        }
        if (matchesArea(changes, "web/", ".vue", ".ts", ".js", ".css")
                && projectDirectory(root, "web", "package.json")) {
            executions.add(execution(com.moxiao.studypilot.agent.runner.RunnerTemplateType.NPM_TEST,
                    "web"));
            reasons.add("Vue/TypeScript 前端发生变化");
        }
        if (matchesArea(changes, "ai-service/", ".py")
                && projectDirectory(root, "ai-service", "pyproject.toml", "requirements.txt")) {
            executions.add(execution(com.moxiao.studypilot.agent.runner.RunnerTemplateType.PYTEST,
                    "ai-service"));
            reasons.add("Python AI 服务发生变化");
        }
        if (executions.isEmpty() && Files.isRegularFile(root.resolve("pom.xml"))) {
            executions.add(execution(com.moxiao.studypilot.agent.runner.RunnerTemplateType.MAVEN_TEST, "."));
            reasons.add("工作区根目录是 Maven 项目");
        } else if (executions.isEmpty() && Files.isRegularFile(root.resolve("package.json"))) {
            executions.add(execution(com.moxiao.studypilot.agent.runner.RunnerTemplateType.NPM_TEST, "."));
            reasons.add("工作区根目录是 npm 项目");
        } else if (executions.isEmpty() && Files.isRegularFile(root.resolve("pyproject.toml"))) {
            executions.add(execution(com.moxiao.studypilot.agent.runner.RunnerTemplateType.PYTEST, "."));
            reasons.add("工作区根目录是 Python 项目");
        }
        List<com.moxiao.studypilot.agent.runner.RunnerTemplateType> templates = executions.stream()
                .map(DeveloperTestRecommendation.RecommendedExecution::templateType).toList();
        return new DeveloperTestRecommendation(workspaceId, templates, List.copyOf(executions),
                false, List.copyOf(reasons));
    }

    public GitCommitPreview previewGitCommit(
            String ownerId, String workspaceId, List<String> requestedPaths, String message
    ) {
        Path root = workspaceRoot(findWorkspace(ownerId, workspaceId));
        List<String> paths = normalizeCommitPaths(root, requestedPaths);
        String normalizedMessage = normalizeCommitMessage(message);
        try (Git git = openGit(root)) {
            var status = git.status().call();
            Set<String> staged = new HashSet<>();
            staged.addAll(status.getAdded());
            staged.addAll(status.getChanged());
            staged.addAll(status.getRemoved());
            if (!staged.isEmpty()) {
                throw new IllegalArgumentException("存在预先暂存的改动，请先处理后再创建提交预览");
            }
            Set<String> changed = new HashSet<>();
            changed.addAll(status.getModified());
            changed.addAll(status.getMissing());
            changed.addAll(status.getUntracked());
            if (!changed.containsAll(paths)) {
                throw new IllegalArgumentException("提交路径必须全部是当前未暂存的真实改动");
            }
            Repository repository = git.getRepository();
            ObjectId head = repository.resolve(Constants.HEAD);
            if (head == null) throw new IllegalArgumentException("Git 仓库尚无初始提交");
            return new GitCommitPreview(workspaceId, repository.getBranch(), head.name(),
                    changeFingerprint(root, paths), paths, normalizedMessage);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("创建 Git 提交预览失败", exception);
        }
    }

    public void validateGitCommit(String ownerId, GitCommitRequest request) {
        GitCommitPreview current = previewGitCommit(
                ownerId, request.workspaceId(), request.paths(), request.message());
        if (!current.expectedHead().equals(request.expectedHead())
                || !current.changeFingerprint().equals(request.changeFingerprint())) {
            throw new ConflictException("Git 改动已变化，请重新创建提交预览");
        }
    }

    public GitCommitResult commitConfirmed(String ownerId, GitCommitRequest request) {
        validateGitCommit(ownerId, request);
        Path root = workspaceRoot(findWorkspace(ownerId, request.workspaceId()));
        synchronized (fileLocks[Math.floorMod(request.workspaceId().hashCode(), fileLocks.length)]) {
            validateGitCommit(ownerId, request);
            try (Git git = openGit(root)) {
                for (String path : request.paths()) {
                    if (Files.exists(root.resolve(path))) {
                        git.add().addFilepattern(path).call();
                    } else {
                        git.rm().addFilepattern(path).call();
                    }
                }
                var commit = git.commit().setMessage(normalizeCommitMessage(request.message())).call();
                return new GitCommitResult(request.workspaceId(), commit.getId().name(),
                        commit.getFullMessage(), List.copyOf(request.paths()), Instant.now());
            } catch (Exception exception) {
                try (Git git = openGit(root)) {
                    git.reset().call();
                } catch (Exception ignored) {
                    // 保留工作区正文，尽力撤销本次暂存区变化。
                }
                throw new IllegalArgumentException("执行 Git commit 失败", exception);
            }
        }
    }

    public GitPushPreview previewGitPush(String ownerId, String workspaceId) {
        Path root = workspaceRoot(findWorkspace(ownerId, workspaceId));
        try (Git git = openGit(root)) {
            Repository repository = git.getRepository();
            String branch = repository.getBranch();
            ObjectId head = repository.resolve(Constants.HEAD);
            if (head == null || branch == null || Constants.HEAD.equals(branch)) {
                throw new IllegalArgumentException("Git 当前不在可推送的本地分支上");
            }
            String remoteRef = "refs/remotes/origin/" + branch;
            ObjectId remote = repository.resolve(remoteRef);
            int ahead = countAhead(git, remote, head);
            return new GitPushPreview(workspaceId, "origin",
                    capturePushDestination(repository).digest(),
                    branch, head.name(), remoteRef,
                    remote == null ? "" : remote.name(), ahead, PUSH_TIMEOUT_SECONDS);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("创建 Git push 预览失败", exception);
        }
    }

    /**
     * push 确认必须绑定预览时的全部事实：远端名、**有效推送目标地址摘要**、分支、本地 HEAD、
     * 期望的远端 ref 及其解析出的提交、有限超时。任一不符即失败关闭，
     * 绝不"照着旧预览推新远端"。
     */
    public void validateGitPush(String ownerId, GitPushRequest request) {
        Path root = workspaceRoot(findWorkspace(ownerId, request.workspaceId()));
        try (Git git = openGit(root)) {
            bindAndValidatePush(git, request);
        } catch (IOException exception) {
            throw new IllegalArgumentException("读取 Git 推送状态失败", exception);
        }
    }

    /**
     * 在锁内**一次性**捕获不可变推送目标并完成全部校验，返回该目标供真正推送使用。
     *
     * <p>捕获之后不再读取可变的 {@code .git/config}，因此"校验通过后配置被改写"无法再改变
     * 实际写入目标。</p>
     */
    private GitPushDestination bindAndValidatePush(Git git, GitPushRequest request) {
        if (!"origin".equals(request.remoteName())) {
            throw new IllegalArgumentException("只允许推送到登记仓库的 origin");
        }
        if (request.timeoutSeconds() < 1 || request.timeoutSeconds() > MAX_PUSH_TIMEOUT_SECONDS) {
            throw new ConflictException("Git push 超时必须为 1 到 " + MAX_PUSH_TIMEOUT_SECONDS + " 秒");
        }
        Repository repository = git.getRepository();
        GitPushDestination destination;
        try {
            destination = capturePushDestination(repository);
        } catch (IllegalArgumentException exception) {
            // 预览阶段是成功的；确认时无法再派生唯一目标（被追加第二个 pushurl、origin 被移除、
            // 或新增了无法保留的 receivepack）属于状态漂移，按冲突处理。
            throw new ConflictException("Git push 目标已变化，请重新创建 push 预览");
        }
        if (!destination.digest().equals(request.remoteUrlDigest())) {
            throw new ConflictException("Git push 目标已变化，请重新创建 push 预览");
        }
        String branch;
        ObjectId head;
        try {
            branch = repository.getBranch();
            head = repository.resolve(Constants.HEAD);
        } catch (Exception exception) {
            throw new IllegalArgumentException("读取 Git 推送状态失败", exception);
        }
        if (head == null || branch == null || Constants.HEAD.equals(branch)
                || !branch.equals(request.branch())
                || !head.name().equals(request.expectedHead())) {
            throw new ConflictException("Git 分支或提交已变化，请重新创建 push 预览");
        }
        String remoteRef = "refs/remotes/origin/" + branch;
        ObjectId remote;
        try {
            remote = repository.resolve(remoteRef);
        } catch (Exception exception) {
            throw new IllegalArgumentException("读取 Git 推送状态失败", exception);
        }
        if (!remoteRef.equals(request.expectedRemoteRef())
                || !(remote == null ? "" : remote.name()).equals(request.expectedRemoteRefCommit())) {
            throw new ConflictException("Git 远端分支已变化，请重新创建 push 预览");
        }
        if (countAhead(git, remote, head) < 1) {
            throw new ConflictException("当前没有需要推送的新提交");
        }
        return destination;
    }

    private int countAhead(Git git, ObjectId remote, ObjectId head) {
        try {
            int ahead = 0;
            for (var ignored : remote == null
                    ? git.log().add(head).call()
                    : git.log().addRange(remote, head).call()) {
                ahead++;
            }
            return ahead;
        } catch (Exception exception) {
            throw new IllegalArgumentException("读取 Git 推送状态失败", exception);
        }
    }

    /**
     * 推送并复核实际写入目标。
     *
     * <p>取得工作区锁后一次性捕获并校验不可变目标，随后**只向该已捕获的 URI** 推送，
     * 不再要求 JGit 按可变的远端名重新解析配置。</p>
     */
    public GitPushResult pushConfirmed(String ownerId, GitPushRequest request) {
        Path root = workspaceRoot(findWorkspace(ownerId, request.workspaceId()));
        synchronized (fileLocks[Math.floorMod(request.workspaceId().hashCode(), fileLocks.length)]) {
            try (Git git = openGit(root)) {
                GitPushDestination destination = bindAndValidatePush(git, request);
                return pushToCapturedDestination(git, destination, request);
            } catch (IOException exception) {
                throw new IllegalArgumentException("读取 Git 推送状态失败", exception);
            }
        }
    }

    /**
     * 针对**已捕获并已校验**的目标执行推送。
     *
     * <p>包级可见：与生产路径共用同一实现；测试可以在捕获目标后修改远端配置，再直接以捕获结果
     * 调用本方法，确定性地验证"捕获之后配置变化不会改变实际去向"，无需仅测试钩子。</p>
     *
     * <p>推送只通过从已捕获 URI 打开的 {@link Transport} 执行，不再要求 JGit 按可变的远端名
     * 重新解析 {@code .git/config}；因此校验通过之后改写配置无法改变实际写入目标。</p>
     */
    GitPushResult pushToCapturedDestination(Git git, GitPushDestination destination, GitPushRequest request) {
        Repository repository = git.getRepository();
        List<RefSpec> specs = List.of(new RefSpec("refs/heads/" + request.branch()));
        try (Transport transport = Transport.open(repository, destination.uri())) {
            // 目标锚点必须在产生任何副作用之前确认；无法确认即失败关闭。
            verifyBoundTransport(transport, destination);
            // JGit 的 PushCommand 从不应用 setTimeout；这里显式施加绑定超时，让超时成为真实执行边界。
            transport.setTimeout(request.timeoutSeconds());
            Collection<RemoteRefUpdate> updates = transport.findRemoteRefUpdatesFor(specs);
            PushResult result = transport.push(NullProgressMonitor.INSTANCE, updates);
            // 纵深防御：JGit 在连接阶段通过 OperationResult.setAdvertisedRefs 记录实际联系的目标，
            // 缺失即为"无法确认"，必须失败而不是静默通过。
            crossCheckReportedDestination(result, destination);
            for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                if (update.getStatus() != RemoteRefUpdate.Status.OK
                        && update.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE) {
                    throw new IllegalStateException("远端拒绝推送: " + update.getStatus());
                }
            }
            return new GitPushResult(request.workspaceId(), "origin", request.branch(),
                    request.expectedHead(), Instant.now());
        } catch (Exception exception) {
            throw new IllegalArgumentException("执行 Git push 失败", exception);
        }
    }

    /** 传输锚点必须等于捕获目标：无法确认或与绑定不一致都在推送前失败关闭。 */
    private static void verifyBoundTransport(Transport transport, GitPushDestination bound) {
        URIish opened = transport.getURI();
        if (opened == null || GitPushDestination.canonicalize(opened).isBlank()) {
            throw new IllegalStateException("无法确认 Git push 的实际目标地址");
        }
        if (!GitPushDestination.canonicalize(opened).equals(bound.canonical())) {
            throw new IllegalStateException("Git push 实际写入目标与确认的目标不一致");
        }
    }

    /**
     * 交叉核对 JGit 实际联系的目标。
     *
     * <p>JGit 在连接阶段用 {@code OperationResult.setAdvertisedRefs(URIish, ...)} 写入该 URI，
     * 因此正常推送都有值；缺失或空白表示"无法确认实际目标"，必须失败关闭，绝不静默通过。
     * 与绑定不一致同样失败。真正的强制发生在推送之前（捕获目标 + 传输锚点），这里只作纵深防御。</p>
     */
    private static void crossCheckReportedDestination(PushResult result, GitPushDestination bound) {
        URIish reported = result.getURI();
        if (reported == null || GitPushDestination.canonicalize(reported).isBlank()) {
            throw new IllegalStateException("无法确认 Git push 的实际写入目标");
        }
        if (!GitPushDestination.canonicalize(reported).equals(bound.canonical())) {
            throw new IllegalStateException("Git push 实际写入目标与确认的目标不一致");
        }
    }

    /**
     * 派生 JGit 实际 PUSH 使用的**唯一**目标地址的摘要。
     *
     * <p>包级可见：生产路径（预览 / 确认 / 推送）与测试共用同一实现，测试可以在捕获目标之后
     * 修改远端配置并验证捕获结果不受影响，无需额外的仅测试钩子。</p>
     */
    GitPushDestination capturePushDestination(Repository repository) {
        return GitPushDestination.resolve(repository.getConfig());
    }

    public CodePatchPreview previewPatch(String ownerId, String workspaceId, String targetFile, String diff) {
        try {
            if (diff != null && diff.length() > MAX_PATCH_CHARACTERS) {
                throw new IllegalArgumentException("补丁内容不能超过 20000 字符");
            }
            if (DeveloperOutputSanitizer.containsCredential(diff)) {
                throw new IllegalArgumentException("补丁疑似包含凭据，禁止进入执行流程");
            }
            Path root = workspaceRoot(findWorkspace(ownerId, workspaceId));
            Path target = resolveReadableFile(root, targetFile);
            if (Files.size(target) > MAX_READ_BYTES) {
                throw new IllegalArgumentException("目标文件超过补丁处理上限");
            }
            byte[] originalBytes = Files.readAllBytes(target);
            UnifiedDiffApplier.PatchResult result = diffApplier.apply(
                    portable(root.relativize(target)), new String(originalBytes, StandardCharsets.UTF_8), diff);
            return new CodePatchPreview(workspaceId, portable(root.relativize(target)), diff,
                    sha256(originalBytes), sha256(result.content().getBytes(StandardCharsets.UTF_8)),
                    false, null, result.affectedLines(), true);
        } catch (IllegalArgumentException | IOException exception) {
            String exposedDiff = DeveloperOutputSanitizer.containsCredential(diff) ? null : diff;
            return new CodePatchPreview(workspaceId, targetFile, exposedDiff, null, null,
                    true, safeMessage(exception), List.of(), false);
        }
    }

    public void validatePatchRequest(String ownerId, ApplyCodePatchRequest request) {
        CodePatchPreview preview = previewPatch(
                ownerId, request.workspaceId(), request.targetFile(), request.unifiedDiff());
        if (!preview.safeToApply()) {
            throw new IllegalArgumentException(preview.conflictReason());
        }
        if (!preview.expectedSha256().equalsIgnoreCase(request.expectedSha256())) {
            throw new ConflictException("目标文件已变化，请重新生成补丁预览");
        }
    }

    public ApplyCodePatchResult applyConfirmedPatch(String ownerId, ApplyCodePatchRequest request) {
        if (DeveloperOutputSanitizer.containsCredential(request.unifiedDiff())) {
            throw new IllegalArgumentException("补丁疑似包含凭据，禁止应用");
        }
        Path root = workspaceRoot(findWorkspace(ownerId, request.workspaceId()));
        Path target = resolveReadableFile(root, request.targetFile());
        String lockKey = request.workspaceId() + ':' + portable(root.relativize(target));
        Object lock = fileLocks[Math.floorMod(lockKey.hashCode(), fileLocks.length)];
        synchronized (lock) {
            try {
                byte[] current = Files.readAllBytes(target);
                if (!sha256(current).equalsIgnoreCase(request.expectedSha256())) {
                    throw new ConflictException("目标文件已变化，请重新生成补丁预览");
                }
                UnifiedDiffApplier.PatchResult result = diffApplier.apply(
                        portable(root.relativize(target)), new String(current, StandardCharsets.UTF_8),
                        request.unifiedDiff());
                byte[] changed = result.content().getBytes(StandardCharsets.UTF_8);
                Set<PosixFilePermission> permissions = posixPermissions(target);
                Path temporary = Files.createTempFile(target.getParent(), ".studypilot-patch-", ".tmp");
                try {
                    Files.write(temporary, changed);
                    if (permissions != null) Files.setPosixFilePermissions(temporary, permissions);
                    try {
                        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException ignored) {
                        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(temporary);
                }
                return new ApplyCodePatchResult(request.workspaceId(), request.targetFile(),
                        true, sha256(changed), "补丁已应用", Instant.now());
            } catch (IOException exception) {
                throw new IllegalArgumentException("应用补丁失败", exception);
            }
        }
    }

    private ProjectWorkspaceEntity findWorkspace(String ownerId, String workspaceId) {
        return workspaceRepository.findByIdAndOwnerId(workspaceId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("工作区不存在: " + workspaceId));
    }

    private Path workspaceRoot(ProjectWorkspaceEntity workspace) {
        try {
            Path configured = Path.of(workspace.getRootPath());
            if (!configured.isAbsolute() || Files.isSymbolicLink(configured)) {
                throw new IllegalArgumentException("工作区路径不再有效");
            }
            Path real = configured.toRealPath();
            if (!Files.isDirectory(real) || !real.toString().equals(workspace.getRootPath())) {
                throw new IllegalArgumentException("工作区路径已变化，请重新登记");
            }
            return real;
        } catch (IOException exception) {
            throw new IllegalArgumentException("工作区路径不存在或不可访问", exception);
        }
    }

    private Path resolveReadableFile(Path root, String rawRelativePath) {
        if (rawRelativePath == null || rawRelativePath.isBlank()) {
            throw new IllegalArgumentException("文件相对路径不能为空");
        }
        Path relative = Path.of(rawRelativePath.trim());
        if (relative.isAbsolute() || relative.getNameCount() == 0) {
            throw new IllegalArgumentException("只允许工作区内的相对路径");
        }
        for (Path part : relative) {
            if (part.toString().equals("..")) throw new IllegalArgumentException("禁止目录穿越");
        }
        if (excluded(relative) || sensitive(relative)) throw new IllegalArgumentException("该文件不可读取");
        Path cursor = root;
        for (Path part : relative) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new IllegalArgumentException("禁止通过符号链接读取文件");
        }
        try {
            Path target = root.resolve(relative).normalize().toRealPath();
            if (!target.startsWith(root) || !Files.isRegularFile(target)) {
                throw new IllegalArgumentException("文件不存在或超出工作区");
            }
            return target;
        } catch (IOException exception) {
            throw new ResourceNotFoundException("文件不存在: " + rawRelativePath);
        }
    }

    private boolean excluded(Path relative) {
        for (Path part : relative) if (EXCLUDED_DIRECTORIES.contains(part.toString())) return true;
        return false;
    }

    private boolean sensitive(Path relative) {
        String name = relative.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.startsWith(".env") || name.equals("application.properties")
                || name.equals("application.yml") || name.equals("application.yaml")
                || name.equals("settings.xml") || name.equals(".npmrc") || name.equals(".pypirc")
                || name.contains("credentials") || name.startsWith("id_rsa")
                || name.startsWith("id_ed25519")) return true;
        if (name.endsWith(".pem") || name.endsWith(".key") || name.endsWith(".p12")
                || name.endsWith(".pfx") || name.endsWith(".jks") || name.endsWith(".keystore")) return true;
        int dot = name.lastIndexOf('.');
        return dot < 0 || !TEXT_EXTENSIONS.contains(name.substring(dot));
    }

    private Git openGit(Path root) throws IOException {
        if (!Files.exists(root.resolve(".git"))) throw new IllegalArgumentException("工作区不是 Git 仓库");
        return Git.open(root.toFile());
    }

    private WorkspaceGitTextResponse clippedGitText(String workspaceId, byte[] bytes) {
        boolean truncated = bytes.length > MAX_GIT_OUTPUT_BYTES;
        int length = Math.min(bytes.length, MAX_GIT_OUTPUT_BYTES);
        return new WorkspaceGitTextResponse(workspaceId,
                DeveloperOutputSanitizer.sanitize(
                        new String(bytes, 0, length, StandardCharsets.UTF_8)), truncated);
    }

    private boolean containsNul(byte[] bytes) {
        for (byte value : bytes) if (value == 0) return true;
        return false;
    }

    private static DeveloperTestRecommendation.RecommendedExecution execution(
            com.moxiao.studypilot.agent.runner.RunnerTemplateType templateType, String workingDirectory
    ) {
        return new DeveloperTestRecommendation.RecommendedExecution(templateType, workingDirectory);
    }

    /**
     * 推荐的子项目目录必须是工作区内真实存在、且不经过符号链接的目录。
     *
     * <p>符号链接会把 Runner 的工作目录指向工作区外部，因此这里直接判定为"该项目不存在"，
     * 而不是把越界路径交给执行层。</p>
     */
    private boolean projectDirectory(Path root, String directory, String... markers) {
        Path candidate = root.resolve(directory);
        if (Files.isSymbolicLink(candidate) || !Files.isDirectory(candidate)) return false;
        try {
            if (!candidate.toRealPath().startsWith(root.toRealPath())) return false;
        } catch (IOException exception) {
            return false;
        }
        for (String marker : markers) {
            if (Files.isRegularFile(candidate.resolve(marker))) return true;
        }
        return false;
    }

    private boolean matchesArea(List<String> files, String prefix, String... extensions) {
        return files.stream().map(this::portableInput).anyMatch(file -> {
            if (!file.startsWith(prefix)) return false;
            for (String extension : extensions) if (file.endsWith(extension)) return true;
            return false;
        });
    }

    private String portableInput(String value) {
        return value == null ? "" : value.trim().replace('\\', '/');
    }

    private List<String> normalizeCommitPaths(Path root, List<String> requestedPaths) {
        if (requestedPaths == null || requestedPaths.isEmpty() || requestedPaths.size() > 50) {
            throw new IllegalArgumentException("Git 提交必须明确指定 1 到 50 个文件");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String raw : requestedPaths) {
            String portable = portableInput(raw);
            Path relative = Path.of(portable);
            if (portable.isBlank() || relative.isAbsolute() || portable.startsWith("../")
                    || portable.contains("/../") || excluded(relative) || sensitive(relative)) {
                throw new IllegalArgumentException("Git 提交包含不安全路径");
            }
            Path target = root.resolve(relative).normalize();
            if (!target.startsWith(root)) throw new IllegalArgumentException("Git 提交路径越界");
            normalized.add(portable);
        }
        return List.copyOf(normalized);
    }

    private String normalizeCommitMessage(String message) {
        String normalized = message == null ? "" : message.trim();
        if (normalized.isEmpty() || normalized.length() > 200 || normalized.contains("\n")
                || DeveloperOutputSanitizer.containsCredential(normalized)) {
            throw new IllegalArgumentException("Git 提交信息必须为不含凭据的单行 1 到 200 字符文本");
        }
        return normalized;
    }

    private String changeFingerprint(Path root, List<String> paths) {
        StringBuilder canonical = new StringBuilder();
        for (String path : paths) {
            Path target = root.resolve(path);
            canonical.append(path).append('\0');
            try {
                canonical.append(Files.exists(target) ? sha256(Files.readAllBytes(target)) : "MISSING");
            } catch (IOException exception) {
                throw new IllegalArgumentException("读取 Git 改动失败", exception);
            }
            canonical.append('\n');
        }
        return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private Set<PosixFilePermission> posixPermissions(Path path) throws IOException {
        try {
            return Files.getPosixFilePermissions(path);
        } catch (UnsupportedOperationException ignored) {
            return null;
        }
    }

    private static Object[] createFileLocks() {
        Object[] locks = new Object[FILE_LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) locks[index] = new Object();
        return locks;
    }

    private String portable(Path path) { return path.toString().replace('\\', '/'); }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
    }

    private String safeMessage(Exception exception) {
        return DeveloperOutputSanitizer.sanitize(
                exception.getMessage() == null ? "补丁无效" : exception.getMessage());
    }

    /** Git 状态里的路径同样要过敏感扫描，避免文件名把凭据带进响应与审计。 */
    private List<String> sanitizedSorted(java.util.Collection<String> values) {
        return values.stream().map(DeveloperOutputSanitizer::sanitize).sorted().toList();
    }
}
