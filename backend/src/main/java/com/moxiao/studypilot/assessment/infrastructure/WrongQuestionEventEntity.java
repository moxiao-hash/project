package com.moxiao.studypilot.assessment.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "wrong_question_events", uniqueConstraints = @UniqueConstraint(
        name = "uk_wrong_event_attempt_question", columnNames = {"attempt_id", "question_id"}
))
public class WrongQuestionEventEntity {
    @Id
    private String id;
    @Column(name = "entry_id", nullable = false, length = 36)
    private String entryId;
    @Column(name = "owner_id", nullable = false, length = 36)
    private String ownerId;
    @Column(name = "attempt_id", nullable = false, length = 36)
    private String attemptId;
    @Column(name = "question_id", nullable = false, length = 36)
    private String questionId;
    @Column(nullable = false)
    private boolean correct;
    @Column(name = "answer_json", nullable = false, columnDefinition = "TEXT")
    private String answerJson;
    @Column(name = "score")
    private Double score;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WrongQuestionEventEntity() {
    }

    public WrongQuestionEventEntity(
            String id, String entryId, String ownerId, String attemptId,
            String questionId, boolean correct, String answerJson, Double score, Instant createdAt
    ) {
        this.id = id;
        this.entryId = entryId;
        this.ownerId = ownerId;
        this.attemptId = attemptId;
        this.questionId = questionId;
        this.correct = correct;
        this.answerJson = answerJson;
        this.score = score;
        this.createdAt = createdAt;
    }
}
