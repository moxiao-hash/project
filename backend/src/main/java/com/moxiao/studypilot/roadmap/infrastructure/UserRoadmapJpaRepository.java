package com.moxiao.studypilot.roadmap.infrastructure;

import com.moxiao.studypilot.roadmap.domain.UserRoadmapStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRoadmapJpaRepository extends JpaRepository<UserRoadmapEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select roadmap from UserRoadmapEntity roadmap where roadmap.id = :id")
    Optional<UserRoadmapEntity> findByIdForUpdate(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select roadmap from UserRoadmapEntity roadmap, QuizEntity quiz
            where quiz.id = :quizId and quiz.ownerId = :ownerId
              and quiz.userRoadmapId = roadmap.id
            """)
    Optional<UserRoadmapEntity> findBoundRoadmapForQuizForUpdate(
            @Param("quizId") String quizId, @Param("ownerId") String ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select roadmap from UserRoadmapEntity roadmap,
              QuizEntity quiz, QuizAttemptEntity attempt, CodingEvaluationJobEntity job
            where job.id = :jobId and job.attemptId = attempt.id
              and attempt.quizId = quiz.id and quiz.userRoadmapId = roadmap.id
            """)
    Optional<UserRoadmapEntity> findBoundRoadmapForCodingJobForUpdate(
            @Param("jobId") String jobId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select roadmap from UserRoadmapEntity roadmap,
              RoadmapQuizGenerationJobEntity job
            where job.id = :jobId and job.userRoadmapId = roadmap.id
            """)
    Optional<UserRoadmapEntity> findBoundRoadmapForQuizJobForUpdate(
            @Param("jobId") String jobId);

    Optional<UserRoadmapEntity> findByOwnerIdAndActiveSlot(String ownerId, String activeSlot);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select roadmap from UserRoadmapEntity roadmap where roadmap.ownerId = :ownerId and roadmap.activeSlot = :activeSlot")
    Optional<UserRoadmapEntity> findByOwnerIdAndActiveSlotForUpdate(
            @Param("ownerId") String ownerId,
            @Param("activeSlot") String activeSlot
    );

    List<UserRoadmapEntity> findAllByOwnerIdAndStatus(String ownerId, UserRoadmapStatus status);

    Optional<UserRoadmapEntity> findByOwnerIdAndTemplateId(String ownerId, String templateId);
}
