package com.moxiao.studypilot.assessment.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

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

    @Column(name = "answers_json", nullable = false, columnDefinition = "TEXT")
    private String answersJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

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
        this.id = id;
        this.quizId = quizId;
        this.ownerId = ownerId;
        this.score = score;
        this.answersJson = answersJson;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }
}
