package com.moxiao.studypilot.material.api;

import com.moxiao.studypilot.material.domain.MaterialCategory;
import com.moxiao.studypilot.user.domain.PrivacyLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateWebMaterialRequest(
        @NotBlank @Size(max = 180) String title,
        @NotBlank @Size(max = 2048) String url,
        @NotNull MaterialCategory category,
        @NotNull PrivacyLevel privacyLevel
) {
}
