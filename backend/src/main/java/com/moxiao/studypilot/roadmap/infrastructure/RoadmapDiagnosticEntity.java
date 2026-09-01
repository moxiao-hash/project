package com.moxiao.studypilot.roadmap.infrastructure;

import com.moxiao.studypilot.roadmap.domain.RoadmapDiagnosticStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "roadmap_diagnostics")
public class RoadmapDiagnosticEntity {
    @Id
    @Column(nullable = false, length = 36)
    private String id;
    @Column(name = "owner_id", nullable = false, length = 36)
    private String ownerId;
    @Column(name = "user_roadmap_id", nullable = false, length = 36)
    private String userRoadmapId;
    @Column(name = "roadmap_template_id", nullable = false, length = 36)
    private String roadmapTemplateId;
    @Column(name = "idempotency_key", nullable = false, length = 180)
    private String idempotencyKey;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private RoadmapDiagnosticStatus status;
    @Column(name = "question_target", nullable = false)
    private int questionTarget;
    @Column(name = "insufficient_question_fallback", nullable = false)
    private boolean insufficientQuestionFallback;
    @Column(name = "node_snapshot_json", nullable = false, columnDefinition = "LONGTEXT")
    private String nodeSnapshotJson;
    @Column(name = "quiz_id", length = 36)
    private String quizId;
    @Column(name = "mastered_node_ids_json", nullable = false, columnDefinition = "LONGTEXT")
    private String masteredNodeIdsJson = "[]";
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RoadmapDiagnosticEntity() { }

    public RoadmapDiagnosticEntity(
            String id, String ownerId, String userRoadmapId, String roadmapTemplateId,
            String idempotencyKey, int questionTarget, boolean insufficientQuestionFallback,
            String nodeSnapshotJson, Instant now
    ) {
        this.id = id;
        this.ownerId = ownerId;
        this.userRoadmapId = userRoadmapId;
        this.roadmapTemplateId = roadmapTemplateId;
        this.idempotencyKey = idempotencyKey;
        this.status = RoadmapDiagnosticStatus.PENDING;
        this.questionTarget = questionTarget;
        this.insufficientQuestionFallback = insufficientQuestionFallback;
        this.nodeSnapshotJson = nodeSnapshotJson;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getUserRoadmapId() { return userRoadmapId; }
    public String getRoadmapTemplateId() { return roadmapTemplateId; }
    public RoadmapDiagnosticStatus getStatus() { return status; }
    public int getQuestionTarget() { return questionTarget; }
    public boolean isInsufficientQuestionFallback() { return insufficientQuestionFallback; }
    public String getNodeSnapshotJson() { return nodeSnapshotJson; }
    public String getQuizId() { return quizId; }
    public String getMasteredNodeIdsJson() { return masteredNodeIdsJson; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void bindQuiz(String quizId, Instant now) {
        if (status != RoadmapDiagnosticStatus.PENDING) {
            throw new IllegalStateException("路线诊断当前不能绑定测验");
        }
        this.quizId = quizId;
        this.status = RoadmapDiagnosticStatus.READY;
        this.updatedAt = now;
    }

    public void complete(String quizId, String masteredNodeIdsJson, Instant now) {
        if (status == RoadmapDiagnosticStatus.COMPLETED) {
            return;
        }
        if (status != RoadmapDiagnosticStatus.READY || !quizId.equals(this.quizId)) {
            throw new IllegalStateException("路线诊断测验绑定不一致");
        }
        this.masteredNodeIdsJson = masteredNodeIdsJson;
        this.status = RoadmapDiagnosticStatus.COMPLETED;
        this.updatedAt = now;
    }
}
