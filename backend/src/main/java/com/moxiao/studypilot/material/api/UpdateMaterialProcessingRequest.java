package com.moxiao.studypilot.material.api;

import com.moxiao.studypilot.material.domain.MaterialProcessingStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UpdateMaterialProcessingRequest(
        @NotNull MaterialProcessingStatus status,
        String summary,
        List<@Size(max = 100) String> tags,
        List<@Size(max = 180) String> knowledgePoints,
        @Size(max = 500) String contentReference,
        @Size(max = 500) String failureReason
) {
    public UpdateMaterialProcessingRequest {
        tags = tags == null ? List.of() : List.copyOf(tags);
        knowledgePoints = knowledgePoints == null ? List.of() : List.copyOf(knowledgePoints);
    }
}
