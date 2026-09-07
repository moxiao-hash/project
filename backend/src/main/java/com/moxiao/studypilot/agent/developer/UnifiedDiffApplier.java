package com.moxiao.studypilot.agent.developer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class UnifiedDiffApplier {
    private static final Pattern HUNK = Pattern.compile(
            "^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");

    PatchResult apply(String targetFile, String original, String diff) {
        if (diff == null || diff.isBlank()) throw new IllegalArgumentException("补丁不能为空");
        List<String> patch = diff.lines().toList();
        if (patch.size() < 3 || !matchesPath(patch.get(0), "--- ", targetFile)
                || !matchesPath(patch.get(1), "+++ ", targetFile)) {
            throw new IllegalArgumentException("补丁文件头与目标文件不一致");
        }
        boolean trailingNewline = original.endsWith("\n");
        List<String> source = new ArrayList<>(List.of(original.split("\n", -1)));
        if (trailingNewline) source.remove(source.size() - 1);
        List<String> output = new ArrayList<>();
        List<String> affected = new ArrayList<>();
        int sourceCursor = 0;
        int index = 2;
        while (index < patch.size()) {
            Matcher matcher = HUNK.matcher(patch.get(index));
            if (!matcher.matches()) throw new IllegalArgumentException("补丁缺少合法 hunk 头");
            int oldStart = Integer.parseInt(matcher.group(1));
            int oldCount = matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2));
            int newStart = Integer.parseInt(matcher.group(3));
            int newCount = matcher.group(4) == null ? 1 : Integer.parseInt(matcher.group(4));
            int hunkStart = Math.max(0, oldStart - 1);
            if (hunkStart < sourceCursor || hunkStart > source.size()) {
                throw new IllegalArgumentException("补丁行号与当前文件不匹配");
            }
            output.addAll(source.subList(sourceCursor, hunkStart));
            sourceCursor = hunkStart;
            index++;
            int consumed = 0;
            int produced = 0;
            while (index < patch.size() && !patch.get(index).startsWith("@@ ")) {
                String line = patch.get(index++);
                if (line.equals("\\ No newline at end of file")) continue;
                if (line.isEmpty()) throw new IllegalArgumentException("补丁行缺少操作前缀");
                char operation = line.charAt(0);
                String value = line.substring(1);
                if (operation == ' ' || operation == '-') {
                    if (sourceCursor >= source.size() || !source.get(sourceCursor).equals(value)) {
                        throw new IllegalArgumentException("补丁上下文与当前文件不一致");
                    }
                    if (operation == ' ') {
                        output.add(value);
                        produced++;
                    }
                    sourceCursor++;
                    consumed++;
                } else if (operation == '+') {
                    output.add(value);
                    produced++;
                } else {
                    throw new IllegalArgumentException("补丁包含不支持的操作");
                }
            }
            if (consumed != oldCount || produced != newCount) {
                throw new IllegalArgumentException("补丁 hunk 行数声明不一致");
            }
            affected.add("Lines " + oldStart + " -> " + newStart);
        }
        output.addAll(source.subList(sourceCursor, source.size()));
        String result = String.join("\n", output) + (trailingNewline ? "\n" : "");
        return new PatchResult(result, List.copyOf(affected));
    }

    private boolean matchesPath(String line, String prefix, String targetFile) {
        if (!line.startsWith(prefix)) return false;
        String value = line.substring(prefix.length()).split("\\t", 2)[0];
        if (value.startsWith("a/") || value.startsWith("b/")) value = value.substring(2);
        return value.equals(targetFile);
    }

    record PatchResult(String content, List<String> affectedLines) { }
}
