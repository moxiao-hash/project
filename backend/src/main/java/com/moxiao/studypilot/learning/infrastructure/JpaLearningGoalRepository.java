package com.moxiao.studypilot.learning.infrastructure;

import com.moxiao.studypilot.learning.domain.LearningGoal;
import com.moxiao.studypilot.learning.domain.LearningGoalRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class JpaLearningGoalRepository implements LearningGoalRepository {

    private final LearningGoalJpaRepository jpaRepository;

    public JpaLearningGoalRepository(LearningGoalJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public LearningGoal save(String ownerId, LearningGoal goal) {
        Instant now = Instant.now();
        LearningGoalEntity entity = new LearningGoalEntity(
                goal.id().toString(),
                ownerId,
                goal.title(),
                goal.targetDate(),
                goal.weeklyStudyHours(),
                goal.status(),
                now,
                now
        );
        return toDomain(jpaRepository.save(entity));
    }

    @Override
    public List<LearningGoal> findAllByOwnerId(String ownerId) {
        return jpaRepository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public boolean existsByIdAndOwnerId(String id, String ownerId) {
        return jpaRepository.existsByIdAndOwnerId(id, ownerId);
    }

    @Override
    public long countByOwnerId(String ownerId) {
        return jpaRepository.countByOwnerId(ownerId);
    }

    private LearningGoal toDomain(LearningGoalEntity entity) {
        return new LearningGoal(
                UUID.fromString(entity.getId()),
                entity.getTitle(),
                entity.getTargetDate(),
                entity.getWeeklyStudyHours(),
                entity.getStatus()
        );
    }
}
