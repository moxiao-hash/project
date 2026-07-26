package com.moxiao.studypilot.material.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CompleteMaterialJobRequest(
        @NotBlank @Size(max = 100) String workerId,
        @Size(max = 20_000) String summary,
        @Size(max = 30) List<@NotBlank @Size(max = 100) String> tags,
        @Size(max = 50) List<@NotBlank @Size(max = 180) String> knowledgePoints,
        @Size(max = 20) List<@NotBlank @Size(max = 500) String> warnings,
        @Size(max = 500) String contentReference,
        @NotEmpty @Size(max = 5_000) List<@Valid Chunk> chunks
) {
    public CompleteMaterialJobRequest {
        tags = tags == null ? List.of() : List.copyOf(tags);
        knowledgePoints = knowledgePoints == null ? List.of() : List.copyOf(knowledgePoints);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
    }

    public record Chunk(
            int position,
            @NotBlank @Size(max = 2_000) String text,
            @NotBlank @Size(max = 255) String locator
    ) {
    }
}
