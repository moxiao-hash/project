package com.moxiao.studypilot.material.api;

import com.moxiao.studypilot.material.domain.MaterialCategory;
import com.moxiao.studypilot.material.domain.MaterialProcessingStatus;
import com.moxiao.studypilot.material.domain.MaterialType;
import com.moxiao.studypilot.material.infrastructure.MaterialEntity;
import com.moxiao.studypilot.user.domain.PrivacyLevel;

import java.util.List;

public record MaterialResponse(
        String id,
        String title,
        MaterialType materialType,
        MaterialCategory category,
        PrivacyLevel privacyLevel,
        String sourceUrl,
        String originalFilename,
        String mediaType,
        Long contentLength,
        MaterialProcessingStatus processingStatus,
        String summary,
        List<String> tags,
        List<String> knowledgePoints,
        List<String> processingWarnings,
        String contentReference,
        String failureReason
) {
    public static MaterialResponse from(MaterialEntity entity) {
        return new MaterialResponse(
                entity.getId(),
                entity.getTitle(),
                entity.getMaterialType(),
                entity.getCategory(),
                entity.getPrivacyLevel(),
                entity.getSourceUrl(),
                entity.getOriginalFilename(),
                entity.getMediaType(),
                entity.getContentLength(),
                entity.getProcessingStatus(),
                entity.getSummary(),
                entity.getTags(),
                entity.getKnowledgePoints(),
                entity.getProcessingWarnings(),
                entity.getContentReference(),
                entity.getFailureReason()
        );
    }
}
