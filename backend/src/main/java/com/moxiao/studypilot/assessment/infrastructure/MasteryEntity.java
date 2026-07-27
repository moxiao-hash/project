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

    @Column(name = "quiz_score")
    private Double quizScore;

    @Column(name = "task_score")
    private Double taskScore;

    @Column(name = "self_assessment_score")
    private Double selfAssessmentScore;

    @Column(name = "evidence_count", nullable = false)
    private int evidenceCount;

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
        this.quizScore = score;
        this.attemptCount = 1;
        this.evidenceCount = 1;
        this.updatedAt = updatedAt;
    }

    public void recordQuiz(double latestScore, double evidenceWeight, Instant now) {
        quizScore = com.moxiao.studypilot.assessment.application.MasteryCalculator
                .updateComponent(quizScore, latestScore, evidenceWeight);
        attemptCount++;
        evidenceCount++;
        recalculate(now);
    }

    public void recordTask(double latestScore, Instant now) {
        taskScore = com.moxiao.studypilot.assessment.application.MasteryCalculator
                .updateComponent(taskScore, latestScore, 1.0);
        evidenceCount++;
        recalculate(now);
    }

    public void recordSelfAssessment(double latestScore, Instant now) {
        selfAssessmentScore = com.moxiao.studypilot.assessment.application.MasteryCalculator
                .updateComponent(selfAssessmentScore, latestScore, 1.0);
        evidenceCount++;
        recalculate(now);
    }

    private void recalculate(Instant now) {
        score = com.moxiao.studypilot.assessment.application.MasteryCalculator
                .combined(quizScore, taskScore, selfAssessmentScore);
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

    public Double getQuizScore() { return quizScore; }
    public Double getTaskScore() { return taskScore; }
    public Double getSelfAssessmentScore() { return selfAssessmentScore; }
    public int getEvidenceCount() { return evidenceCount; }
    public Instant getUpdatedAt() { return updatedAt; }
}
