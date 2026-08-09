package com.moxiao.studypilot.roadmap.infrastructure;

import com.moxiao.studypilot.roadmap.domain.RoadmapPublicationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoadmapTemplateJpaRepository extends JpaRepository<RoadmapTemplateEntity, String> {
    Optional<RoadmapTemplateEntity> findByRoadmapCodeAndTemplateVersion(
            String roadmapCode,
            int templateVersion
    );

    Optional<RoadmapTemplateEntity> findFirstByRoadmapCodeAndPublicationStatusOrderByTemplateVersionDesc(
            String roadmapCode,
            RoadmapPublicationStatus publicationStatus
    );
}
