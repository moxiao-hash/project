package com.moxiao.studypilot.roadmap.api;

import com.moxiao.studypilot.roadmap.infrastructure.RoadmapTemplateEntity;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapEntity;

import java.time.Instant;

public record RoadmapEnrollmentResponse(
        String id,
        String roadmapCode,
        int templateVersion,
        String title,
        String status,
        Instant enrolledAt
) {
    public static RoadmapEnrollmentResponse from(
            UserRoadmapEntity enrollment,
            RoadmapTemplateEntity template
    ) {
        return new RoadmapEnrollmentResponse(
                enrollment.getId(),
                template.getRoadmapCode(),
                template.getTemplateVersion(),
                template.getTitle(),
                enrollment.getStatus().name(),
                enrollment.getEnrolledAt()
        );
    }
}
