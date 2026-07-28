package com.moxiao.studypilot.aicredential.domain;

import java.util.Locale;

public enum AiProvider {
    DEEPSEEK,
    TAVILY;

    public static AiProvider fromPath(String value) {
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("不支持的 AI 服务商");
        }
    }
}
