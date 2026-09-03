package com.moxiao.studypilot.assessment.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import com.moxiao.studypilot.assessment.domain.QuizAttemptStatus;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "quiz_attempts")
public class QuizAttemptEntity {

    @Id
    private String id;

    @Column(name = "quiz_id", nullable = false)
    private String quizId;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(nullable = false)
    private double score;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private QuizAttemptStatus status;

    @Column(name = "idempotency_key", nullable = false, length = 180)
    private String idempotencyKey;

    @Column(name = "objective_score", nullable = false)
    private double objectiveScore;

    @Column(name = "evaluation_json", columnDefinition = "TEXT")
    private String evaluationJson;

    @Column(length = 500)
    private String warning;

    @Column(name = "answers_json", nullable = false, columnDefinition = "TEXT")
    private String answersJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected QuizAttemptEntity() {
    }

    public QuizAttemptEntity(
            String id,
            String quizId,
            String ownerId,
            double score,
            String answersJson,
            Instant createdAt
    ) {
        this(
                id, quizId, ownerId, score, QuizAttemptStatus.GRADED,
                UUID.randomUUID().toString(), score, answersJson, null, null,
                createdAt
        );
    }

    public QuizAttemptEntity(
            String id,
            String quizId,
            String ownerId,
            double score,
            QuizAttemptStatus status,
            String idempotencyKey,
            double objectiveScore,
            String answersJson,
            String evaluationJson,
            String warning,
            Instant createdAt
    ) {
        this.id = id;
        this.quizId = quizId;
        this.ownerId = ownerId;
        this.score = score;
        this.status = status;
        this.idempotencyKey = idempotencyKey;
        this.objectiveScore = objectiveScore;
        this.answersJson = answersJson;
        this.evaluationJson = evaluationJson;
        this.warning = warning;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getQuizId() { return quizId; }
    public String getOwnerId() { return ownerId; }
    public double getScore() { return score; }
    public double getObjectiveScore() { return objectiveScore; }
    public QuizAttemptStatus getStatus() { return status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getAnswersJson() { return answersJson; }
    public String getEvaluationJson() { return evaluationJson; }
    public String getWarning() { return warning; }
    public Instant getCreatedAt() { return createdAt; }

    public void complete(double finalScore, String evaluationJson, Instant now) {
        this.score = finalScore;
        this.evaluationJson = evaluationJson;
        this.warning = "未执行代码，不保证代码可以编译或运行";
        this.status = QuizAttemptStatus.GRADED;
        this.updatedAt = now;
    }

    public void partiallyGrade(String reason, Instant now) {
        this.score = objectiveScore;
        this.warning = reason;
        this.status = QuizAttemptStatus.PARTIALLY_GRADED;
        this.updatedAt = now;
    }
}
