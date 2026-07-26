package com.moxiao.studypilot.material.api;

import com.moxiao.studypilot.material.domain.MaterialCategory;
import com.moxiao.studypilot.material.domain.MaterialJobStatus;
import com.moxiao.studypilot.material.domain.MaterialType;
import com.moxiao.studypilot.material.infrastructure.MaterialEntity;
import com.moxiao.studypilot.material.infrastructure.MaterialProcessingJobEntity;
import com.moxiao.studypilot.user.domain.PrivacyLevel;

import java.time.Instant;

public record MaterialJobResponse(
        String jobId,
        String materialId,
        String ownerId,
        String title,
        MaterialType materialType,
        MaterialCategory category,
        PrivacyLevel privacyLevel,
        String sourceUrl,
        String originalFilename,
        String mediaType,
        MaterialJobStatus status,
        int attemptCount,
        Instant leaseExpiresAt
) {
    public static MaterialJobResponse from(
            MaterialProcessingJobEntity job,
            MaterialEntity material
    ) {
        return new MaterialJobResponse(
                job.getId(),
                material.getId(),
                material.getOwnerId(),
                material.getTitle(),
                material.getMaterialType(),
                material.getCategory(),
                material.getPrivacyLevel(),
                material.getSourceUrl(),
                material.getOriginalFilename(),
                material.getMediaType(),
                job.getStatus(),
                job.getAttemptCount(),
                job.getLeaseExpiresAt()
        );
    }
}
