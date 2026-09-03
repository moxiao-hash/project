package com.moxiao.studypilot.assessment.infrastructure;

import com.moxiao.studypilot.assessment.domain.WrongQuestionReviewStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "wrong_question_reviews", uniqueConstraints = @UniqueConstraint(
        name = "uk_wrong_review_owner_key", columnNames = {"owner_id", "idempotency_key"}
))
public class WrongQuestionReviewEntity {
    @Id private String id;
    @Column(name = "owner_id", nullable = false, length = 36) private String ownerId;
    @Column(name = "quiz_id", nullable = false, length = 36) private String quizId;
    @Column(name = "idempotency_key", nullable = false, length = 180) private String idempotencyKey;
    @Column(name = "chapter_key", length = 180) private String chapterKey;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20) private WrongQuestionReviewStatus status;
    @Column(name = "question_count", nullable = false) private int questionCount;
    @Column(name = "cleared_count", nullable = false) private int clearedCount;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "completed_at") private Instant completedAt;

    protected WrongQuestionReviewEntity() {
    }

    public WrongQuestionReviewEntity(
            String id, String ownerId, String quizId, String idempotencyKey,
            String chapterKey, int questionCount, Instant now
    ) {
        this.id = id;
        this.ownerId = ownerId;
        this.quizId = quizId;
        this.idempotencyKey = idempotencyKey;
        this.chapterKey = chapterKey;
        this.status = WrongQuestionReviewStatus.OPEN;
        this.questionCount = questionCount;
        this.clearedCount = 0;
        this.createdAt = now;
    }

    public void complete(int clearedCount, Instant now) {
        this.status = WrongQuestionReviewStatus.COMPLETED;
        this.clearedCount = clearedCount;
        this.completedAt = now;
    }

    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getQuizId() { return quizId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getChapterKey() { return chapterKey; }
    public WrongQuestionReviewStatus getStatus() { return status; }
    public int getQuestionCount() { return questionCount; }
    public int getClearedCount() { return clearedCount; }
}
