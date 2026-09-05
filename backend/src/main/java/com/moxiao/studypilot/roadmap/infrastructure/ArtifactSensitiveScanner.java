package com.moxiao.studypilot.roadmap.infrastructure;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Component
public class ArtifactSensitiveScanner {

    private static final List<String> SENSITIVE_EXTENSIONS = List.of(
            ".env", ".key", ".pem", ".pkcs12", ".p12", ".pfx", ".secret"
    );

    private static final List<Pattern> SENSITIVE_NAME_PATTERNS = List.of(
            Pattern.compile(".*id_rsa.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*id_ed25519.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*credentials.*", Pattern.CASE_INSENSITIVE)
    );

    private static final List<Pattern> CONTENT_PATTERNS = List.of(
            Pattern.compile("(?i)(api[_-]?key|secret[_-]?key|private[_-]?key)\\s*[:=]\\s*['\"][a-zA-Z0-9_\\-]{16,}['\"]"),
            Pattern.compile("-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----")
    );

    public record ScanResult(boolean passed, String findings) {
    }

    public ScanResult scan(Path directory) {
        if (!Files.exists(directory)) {
            return new ScanResult(false, "目录不存在: " + directory);
        }

        List<String> findings = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(directory, 5)) {
            stream.filter(Files::isRegularFile).forEach(file -> {
                String fileName = file.getFileName().toString();
                for (String ext : SENSITIVE_EXTENSIONS) {
                    if (fileName.toLowerCase().endsWith(ext)) {
                        findings.add("检测到敏感文件扩展名: " + file.getFileName());
                        return;
                    }
                }
                for (Pattern p : SENSITIVE_NAME_PATTERNS) {
                    if (p.matcher(fileName).matches()) {
                        findings.add("检测到敏感文件名: " + file.getFileName());
                        return;
                    }
                }

                try {
                    if (Files.size(file) < 1024 * 1024) {
                        String content = Files.readString(file);
                        for (Pattern cp : CONTENT_PATTERNS) {
                            if (cp.matcher(content).find()) {
                                findings.add("文件包含敏感凭据格式: " + file.getFileName());
                                return;
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            });
        } catch (IOException e) {
            return new ScanResult(false, "扫描目录失败: " + e.getMessage());
        }

        if (findings.isEmpty()) {
            return new ScanResult(true, null);
        }
        return new ScanResult(false, String.join("; ", findings));
    }
}
