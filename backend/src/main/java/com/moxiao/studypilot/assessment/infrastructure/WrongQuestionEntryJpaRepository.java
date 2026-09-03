package com.moxiao.studypilot.assessment.infrastructure;

import com.moxiao.studypilot.assessment.domain.WrongQuestionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WrongQuestionEntryJpaRepository
        extends JpaRepository<WrongQuestionEntryEntity, String> {
    Optional<WrongQuestionEntryEntity> findByOwnerIdAndSourceQuestionId(
            String ownerId, String sourceQuestionId);

    List<WrongQuestionEntryEntity> findAllByOwnerIdAndStatus(
            String ownerId, WrongQuestionStatus status);

    List<WrongQuestionEntryEntity> findAllByOwnerId(String ownerId);

    long countByOwnerIdAndStatus(String ownerId, WrongQuestionStatus status);
}
