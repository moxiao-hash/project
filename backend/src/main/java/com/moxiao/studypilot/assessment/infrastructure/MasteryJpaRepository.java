package com.moxiao.studypilot.assessment.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MasteryJpaRepository extends JpaRepository<MasteryEntity, String> {

    Optional<MasteryEntity> findByOwnerIdAndKnowledgePoint(String ownerId, String knowledgePoint);

    List<MasteryEntity> findAllByOwnerIdOrderByScoreAsc(String ownerId);

    long countByOwnerIdAndScoreLessThan(String ownerId, double score);
}
