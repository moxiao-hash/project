package com.moxiao.studypilot.roadmap.infrastructure;

import com.moxiao.studypilot.roadmap.domain.ArtifactEvaluationMode;
import com.moxiao.studypilot.roadmap.domain.ArtifactStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "roadmap_artifacts")
public class RoadmapArtifactEntity {
    @Id
    @Column(nullable = false, length = 36)
    private String id;
    @Column(name = "owner_id", nullable = false, length = 36)
    private String ownerId;
    @Column(name = "workspace_id", nullable = false, length = 36)
    private String workspaceId;
    @Column(name = "user_roadmap_id", nullable = false, length = 36)
    private String userRoadmapId;
    @Column(name = "user_roadmap_node_id", nullable = false, length = 36)
    private String userRoadmapNodeId;
    @Column(name = "roadmap_node_id", nullable = false, length = 100)
    private String roadmapNodeId;
    @Column(name = "roadmap_module_id", nullable = false, length = 100)
    private String roadmapModuleId;
    @Column(name = "roadmap_stage_id", nullable = false, length = 80)
    private String roadmapStageId;
    @Column(name = "node_title", nullable = false, length = 180)
    private String nodeTitle;
    @Column(name = "module_title", nullable = false, length = 180)
    private String moduleTitle;
    @Column(name = "stage_title", nullable = false, length = 180)
    private String stageTitle;
    @Column(name = "relative_path", nullable = false, length = 1024)
    private String relativePath;
    @Column(name = "canonical_path", nullable = false, length = 1024)
    private String canonicalPath;
    @Column(nullable = false, length = 2000)
    private String description;
    @Column(name = "test_evidence", nullable = false, length = 4000)
    private String testEvidence;
    @Enumerated(EnumType.STRING)
    @Column(name = "evaluation_mode", nullable = false, length = 30)
    private ArtifactEvaluationMode evaluationMode;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ArtifactStatus status;
    @Column(name = "submission_version", nullable = false)
    private int submissionVersion;
    @Column(name = "idempotency_key", nullable = false, length = 180)
    private String idempotencyKey;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "rubric_score")
    private Integer rubricScore;
    @Column(name = "rubric_feedback", length = 4000)
    private String rubricFeedback;
    @Column(name = "sensitive_scan_passed")
    private Boolean sensitiveScanPassed = true;
    @Column(name = "sensitive_findings", length = 2000)
    private String sensitiveFindings;
    @Column(name = "accepted_at")
    private Instant acceptedAt;
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected RoadmapArtifactEntity() { }

    public RoadmapArtifactEntity(
            String id, String ownerId, String workspaceId, String userRoadmapId,
            String userRoadmapNodeId, String roadmapNodeId, String roadmapModuleId,
            String roadmapStageId, String nodeTitle, String moduleTitle, String stageTitle,
            String relativePath, String canonicalPath, String description, String testEvidence,
            ArtifactEvaluationMode evaluationMode, int submissionVersion,
            String idempotencyKey, Instant now
    ) {
        this.id = id;
        this.ownerId = ownerId;
        this.workspaceId = workspaceId;
        this.userRoadmapId = userRoadmapId;
        this.userRoadmapNodeId = userRoadmapNodeId;
        this.roadmapNodeId = roadmapNodeId;
        this.roadmapModuleId = roadmapModuleId;
        this.roadmapStageId = roadmapStageId;
        this.nodeTitle = nodeTitle;
        this.moduleTitle = moduleTitle;
        this.stageTitle = stageTitle;
        this.relativePath = relativePath;
        this.canonicalPath = canonicalPath;
        this.description = description;
        this.testEvidence = testEvidence;
        this.evaluationMode = evaluationMode;
        this.status = ArtifactStatus.SUBMITTED;
        this.submissionVersion = submissionVersion;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getWorkspaceId() { return workspaceId; }
    public String getUserRoadmapId() { return userRoadmapId; }
    public String getUserRoadmapNodeId() { return userRoadmapNodeId; }
    public String getRoadmapNodeId() { return roadmapNodeId; }
    public String getRoadmapModuleId() { return roadmapModuleId; }
    public String getRoadmapStageId() { return roadmapStageId; }
    public String getNodeTitle() { return nodeTitle; }
    public String getModuleTitle() { return moduleTitle; }
    public String getStageTitle() { return stageTitle; }
    public String getRelativePath() { return relativePath; }
    public String getCanonicalPath() { return canonicalPath; }
    public String getDescription() { return description; }
    public String getTestEvidence() { return testEvidence; }
    public ArtifactEvaluationMode getEvaluationMode() { return evaluationMode; }
    public ArtifactStatus getStatus() { return status; }
    public int getSubmissionVersion() { return submissionVersion; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void recordReview(int score, String feedback, boolean sensitivePassed, String sensitiveFindings, Instant now) {
        this.rubricScore = score;
        this.rubricFeedback = feedback;
        this.sensitiveScanPassed = sensitivePassed;
        this.sensitiveFindings = sensitiveFindings;
        if (!sensitivePassed) {
            this.status = ArtifactStatus.REJECTED;
        }
        this.updatedAt = now;
    }

    public void accept(Instant now) {
        if (this.status == ArtifactStatus.REJECTED) {
            throw new IllegalArgumentException("已被拒绝的成果物无法直接接受");
        }
        if (this.rubricScore != null && this.rubricScore < 70) {
            throw new IllegalArgumentException("评分低于70分（当前" + this.rubricScore + "分），无法通过验收");
        }
        this.status = ArtifactStatus.ACCEPTED;
        this.acceptedAt = now;
        this.updatedAt = now;
    }

    public void reject(String reason, Instant now) {
        this.status = ArtifactStatus.REJECTED;
        this.rubricFeedback = reason;
        this.updatedAt = now;
    }

    public Integer getRubricScore() { return rubricScore; }
    public String getRubricFeedback() { return rubricFeedback; }
    public Boolean getSensitiveScanPassed() { return sensitiveScanPassed; }
    public String getSensitiveFindings() { return sensitiveFindings; }
    public Instant getAcceptedAt() { return acceptedAt; }

}
