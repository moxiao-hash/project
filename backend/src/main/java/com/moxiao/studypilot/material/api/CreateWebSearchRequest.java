package com.moxiao.studypilot.material.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateWebSearchRequest(
        @NotBlank String ownerId,
        @NotBlank @Size(max = 500) String query,
        @Size(max = 100) String providerRequestId,
        @NotEmpty @Size(max = 10) List<@Valid Result> results
) {
    public record Result(
            @NotBlank @Size(max = 300) String title,
            @NotBlank @Size(max = 2048) String url,
            @Size(max = 2_000) String snippet,
            double score
    ) {
    }
}
