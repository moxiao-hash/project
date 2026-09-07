package com.moxiao.studypilot.agent.developer;

import com.moxiao.studypilot.roadmap.infrastructure.ProjectWorkspaceEntity;
import com.moxiao.studypilot.roadmap.infrastructure.ProjectWorkspaceJpaRepository;
import com.moxiao.studypilot.shared.error.ConflictException;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

@Service
public class WorkspaceDeveloperService {
    private static final int MAX_TREE_ENTRIES = 2_000;
    private static final int MAX_READ_BYTES = 64 * 1024;
    private static final int MAX_SEARCH_FILES = 2_000;
    private static final int MAX_SEARCH_MATCHES = 50;
    private static final int MAX_GIT_OUTPUT_BYTES = 64 * 1024;
    private static final int MAX_PATCH_CHARACTERS = 20_000;
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
                        portable(relative), candidate.getFileName().toString(), directory,
                        directory ? 0 : Files.size(candidate)));
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
            return new WorkspaceFileReadResponse(workspaceId, portable(root.relativize(target)),
                    DeveloperOutputSanitizer.sanitize(new String(bytes, StandardCharsets.UTF_8)),
                    size, false);
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
                    modified.stream().sorted().toList(), status.getUntracked().stream().sorted().toList());
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
        return exception.getMessage() == null ? "补丁无效" : exception.getMessage();
    }
}
