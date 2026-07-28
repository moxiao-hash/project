package com.moxiao.studypilot.aicredential.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateAiCredentialRequest(
        @NotBlank(message = "API Key 不能为空")
        @Size(min = 8, max = 512, message = "API Key 长度必须在 8 到 512 个字符之间")
        String apiKey
) {
}
