package com.moxiao.studypilot.aicredential.api;

public record AiProviderStatusResponse(
        boolean configured,
        String source,
        String maskedSuffix,
        boolean available
) {
}
