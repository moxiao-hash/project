package com.moxiao.studypilot.roadmap.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.Collection;

public interface LegacyLearningEvidenceJpaRepository
        extends JpaRepository<LegacyLearningEvidenceEntity, String> {
    List<LegacyLearningEvidenceEntity> findAllByOwnerId(String ownerId);

    List<LegacyLearningEvidenceEntity> findAllByOwnerIdAndLessonId(String ownerId, String lessonId);

    Optional<LegacyLearningEvidenceEntity> findByOwnerIdAndLessonIdAndMigrationVersion(
            String ownerId,
            String lessonId,
            int migrationVersion
    );

    List<LegacyLearningEvidenceEntity> findAllByOwnerIdAndMigrationVersionAndLessonIdIn(
            String ownerId,
            int migrationVersion,
            Collection<String> lessonIds
    );
}
