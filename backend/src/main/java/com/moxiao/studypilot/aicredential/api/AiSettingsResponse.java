package com.moxiao.studypilot.aicredential.api;

public record AiSettingsResponse(
        String modelProvider,
        String modelName,
        AiProviderStatusResponse deepseek,
        AiProviderStatusResponse tavily,
        boolean deepseekConfigured,
        String deepseekMaskedSuffix,
        boolean tavilyConfigured,
        String tavilyMaskedSuffix,
        String warning
) {
}
