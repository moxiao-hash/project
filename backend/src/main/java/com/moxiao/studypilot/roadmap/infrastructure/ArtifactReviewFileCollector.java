package com.moxiao.studypilot.roadmap.infrastructure;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 为 AI Rubric 评审构造最小、可复核的源码清单。
 *
 * <p>本类只读取成果目录中的普通文本源码。配置、凭据、缓存、构建结果、二进制
 * 文件和符号链接永远不会进入清单。清单中的摘要用于确认后再次校验，防止用户
 * 确认的文件与真正发送给模型的文件不一致。</p>
 */
@Component
public class ArtifactReviewFileCollector {
    private static final int MAX_FILES = 30;
    private static final long MAX_FILE_BYTES = 128 * 1024;
    private static final long MAX_TOTAL_BYTES = 512 * 1024;
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            ".git", ".idea", ".ssh", ".aws", ".venv", "venv", "node_modules",
            "target", "dist", "build", "out", "__pycache__"
    );
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".java", ".kt", ".py", ".js", ".ts", ".vue", ".html", ".css",
            ".scss", ".sql", ".xml", ".md", ".txt", ".json", ".yaml", ".yml"
    );
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)(api[_-]?key|secret[_-]?key|access[_-]?token|private[_-]?key|password)"
                    + "\\s*[:=]\\s*['\\\"]?[A-Za-z0-9_./+\\-=]{16,}"
    );
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----"
    );

    public CollectionResult collect(Path root) {
        Path canonical = canonicalRoot(root);
        Path relativeRoot = Files.isDirectory(canonical) ? canonical : canonical.getParent();
        List<FileSnapshot> files = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        List<String> findings = new ArrayList<>();
        long totalBytes = 0;
        try (Stream<Path> stream = Files.walk(canonical)) {
            List<Path> candidates = stream.sorted(Comparator.comparing(Path::toString)).toList();
            for (Path candidate : candidates) {
                if (!Files.isRegularFile(candidate) || Files.isSymbolicLink(candidate)) {
                    continue;
                }
                String relative = relativeRoot.relativize(candidate).toString().replace('\\', '/');
                if (excluded(candidate, relativeRoot)) {
                    excluded.add(relative);
                    continue;
                }
                long size = Files.size(candidate);
                if (size > MAX_FILE_BYTES) {
                    excluded.add(relative);
                    continue;
                }
                byte[] content = Files.readAllBytes(candidate);
                if (containsNul(content)) {
                    excluded.add(relative);
                    continue;
                }
                String text = new String(content, StandardCharsets.UTF_8);
                if (CREDENTIAL.matcher(text).find() || PRIVATE_KEY.matcher(text).find()) {
                    findings.add("源码中检测到疑似凭据: " + relative);
                    continue;
                }
                if (files.size() >= MAX_FILES || totalBytes + size > MAX_TOTAL_BYTES) {
                    throw new IllegalArgumentException("成果源码超过 AI 评审安全上限");
                }
                files.add(new FileSnapshot(relative, size, sha256(content)));
                totalBytes += size;
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法读取成果源码", exception);
        }
        if (!findings.isEmpty()) {
            return new CollectionResult(false, List.of(), List.copyOf(excluded),
                    String.join("; ", findings), 0);
        }
        if (files.isEmpty()) {
            return new CollectionResult(false, List.of(), List.copyOf(excluded),
                    "没有可发送给 AI 评审的文本源码", 0);
        }
        return new CollectionResult(true, List.copyOf(files), List.copyOf(excluded), null,
                totalBytes);
    }

    private Path canonicalRoot(Path root) {
        try {
            Path canonical = root.toRealPath();
            if (!Files.isDirectory(canonical) && !Files.isRegularFile(canonical)) {
                throw new IllegalArgumentException("成果路径必须是普通文件或目录");
            }
            return canonical;
        } catch (IOException exception) {
            throw new IllegalArgumentException("成果路径不存在或不可访问", exception);
        }
    }

    private boolean excluded(Path file, Path root) {
        Path relative = root.relativize(file);
        for (Path part : relative) {
            if (EXCLUDED_DIRECTORIES.contains(part.toString())) {
                return true;
            }
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.equals(".env") || (name.startsWith(".env.") && !name.equals(".env.example"))) {
            return true;
        }
        if (name.equals("application.properties") || name.equals("application.yml")
                || name.equals("application.yaml") || name.equals("settings.xml")
                || name.equals(".npmrc") || name.equals(".pypirc")) {
            return true;
        }
        if (name.endsWith(".pem") || name.endsWith(".key") || name.endsWith(".p12")
                || name.endsWith(".pfx") || name.endsWith(".jks")
                || name.endsWith(".keystore")) {
            return true;
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 || !TEXT_EXTENSIONS.contains(name.substring(dot));
    }

    private boolean containsNul(byte[] content) {
        for (byte value : content) {
            if (value == 0) {
                return true;
            }
        }
        return false;
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
    }

    public record FileSnapshot(String relativePath, long size, String sha256) { }

    public record CollectionResult(
            boolean passed,
            List<FileSnapshot> files,
            List<String> excludedPaths,
            String findings,
            long totalBytes
    ) { }
}
