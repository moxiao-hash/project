package com.moxiao.studypilot.roadmap.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RoadmapArtifactReviewJpaRepository extends JpaRepository<RoadmapArtifactReviewEntity, String> {
    List<RoadmapArtifactReviewEntity> findAllByArtifactIdOrderByCreatedAtAsc(String artifactId);
}
