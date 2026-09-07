package com.moxiao.studypilot.agent.developer;

import java.util.regex.Pattern;

final class DeveloperOutputSanitizer {
    private static final String REDACTED = "[REDACTED]";
    private static final Pattern NAMED_CREDENTIAL = Pattern.compile(
            "(?i)(api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret)"
                    + "(\\s*[:=]\\s*[\\\"']?)([^\\s\\\"',;}{]{8,})");
    private static final Pattern PROVIDER_TOKEN = Pattern.compile(
            "(?i)\\b(sk-[a-z0-9_-]{12,})\\b");
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----");

    private DeveloperOutputSanitizer() {
    }

    static String sanitize(String content) {
        if (content == null || content.isEmpty()) return content;
        String sanitized = PRIVATE_KEY.matcher(content).replaceAll(REDACTED);
        sanitized = NAMED_CREDENTIAL.matcher(sanitized).replaceAll("$1$2" + REDACTED);
        return PROVIDER_TOKEN.matcher(sanitized).replaceAll(REDACTED);
    }

    static boolean containsCredential(String content) {
        if (content == null || content.isEmpty()) return false;
        return PRIVATE_KEY.matcher(content).find()
                || NAMED_CREDENTIAL.matcher(content).find()
                || PROVIDER_TOKEN.matcher(content).find();
    }
}
