package com.moxiao.studypilot.material.api;

import com.moxiao.studypilot.material.domain.MaterialCategory;
import com.moxiao.studypilot.user.domain.PrivacyLevel;
import jakarta.validation.constraints.NotNull;

public record ImportWebSearchResultRequest(
        @NotNull MaterialCategory category,
        @NotNull PrivacyLevel privacyLevel
) {
}
