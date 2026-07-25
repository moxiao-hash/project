package com.moxiao.studypilot.assessment.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "mastery_records")
public class MasteryEntity {

    @Id
    private String id;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(name = "knowledge_point", nullable = false, length = 180)
    private String knowledgePoint;

    @Column(nullable = false)
    private double score;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MasteryEntity() {
    }

    public MasteryEntity(
            String id,
            String ownerId,
            String knowledgePoint,
            double score,
            Instant updatedAt
    ) {
        this.id = id;
        this.ownerId = ownerId;
        this.knowledgePoint = knowledgePoint;
        this.score = score;
        this.attemptCount = 1;
        this.updatedAt = updatedAt;
    }

    public void record(double latestScore, Instant now) {
        score = ((score * attemptCount) + latestScore) / (attemptCount + 1);
        attemptCount++;
        updatedAt = now;
    }

    public String getKnowledgePoint() {
        return knowledgePoint;
    }

    public double getScore() {
        return score;
    }

    public int getAttemptCount() {
        return attemptCount;
    }
}
