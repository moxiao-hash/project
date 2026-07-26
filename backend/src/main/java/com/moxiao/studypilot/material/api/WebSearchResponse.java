package com.moxiao.studypilot.material.api;

import com.moxiao.studypilot.material.infrastructure.WebSearchResultEntity;
import com.moxiao.studypilot.material.infrastructure.WebSearchSessionEntity;

import java.time.Instant;
import java.util.List;

public record WebSearchResponse(
        String id,
        String ownerId,
        String query,
        String providerRequestId,
        Instant searchedAt,
        List<Result> results
) {
    public static WebSearchResponse from(
            WebSearchSessionEntity session,
            List<WebSearchResultEntity> results
    ) {
        return new WebSearchResponse(
                session.getId(),
                session.getOwnerId(),
                session.getQuery(),
                session.getProviderRequestId(),
                session.getSearchedAt(),
                results.stream().map(Result::from).toList()
        );
    }

    public record Result(
            String id,
            String title,
            String url,
            String snippet,
            double score,
            String importedMaterialId
    ) {
        private static Result from(WebSearchResultEntity entity) {
            return new Result(
                    entity.getId(),
                    entity.getTitle(),
                    entity.getUrl(),
                    entity.getSnippet(),
                    entity.getScore(),
                    entity.getImportedMaterialId()
            );
        }
    }
}
