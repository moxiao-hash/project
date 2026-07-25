package com.moxiao.studypilot.material.api;

import com.moxiao.studypilot.material.domain.MaterialCategory;
import com.moxiao.studypilot.material.domain.MaterialType;
import com.moxiao.studypilot.user.domain.PrivacyLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateMaterialRequest(
        @NotBlank @Size(max = 180) String title,
        @NotNull MaterialType materialType,
        @NotNull MaterialCategory category,
        @NotNull PrivacyLevel privacyLevel,
        @Size(max = 2048) String sourceUrl
) {
}
