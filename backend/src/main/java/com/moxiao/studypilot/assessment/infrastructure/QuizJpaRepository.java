package com.moxiao.studypilot.assessment.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface QuizJpaRepository extends JpaRepository<QuizEntity, String> {

    Optional<QuizEntity> findByIdAndOwnerId(String id, String ownerId);

    List<QuizEntity> findAllByOwnerIdOrderByCreatedAtDesc(String ownerId);
}
