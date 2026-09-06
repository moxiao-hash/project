package com.moxiao.studypilot.roadmap.infrastructure;

import com.moxiao.studypilot.roadmap.domain.ArtifactReviewPreviewStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "artifact_review_previews")
public class ArtifactReviewPreviewEntity {
    @Id
    @Column(nullable = false, length = 36)
    private String id;
    @Column(name = "artifact_id", nullable = false, length = 36)
    private String artifactId;
    @Column(name = "owner_id", nullable = false, length = 36)
    private String ownerId;
    @Column(name = "runner_execution_id", nullable = false, length = 36)
    private String runnerExecutionId;
    @Column(name = "runner_template_type", nullable = false, length = 40)
    private String runnerTemplateType;
    @Column(name = "runner_summary", nullable = false, length = 4000)
    private String runnerSummary;
    @Column(name = "manifest_json", nullable = false, columnDefinition = "TEXT")
    private String manifestJson;
    @Column(name = "excluded_paths_json", nullable = false, columnDefinition = "TEXT")
    private String excludedPathsJson;
    @Column(name = "snapshot_digest", nullable = false, length = 64)
    private String snapshotDigest;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ArtifactReviewPreviewStatus status;
    @Column(name = "idempotency_key", nullable = false, length = 180)
    private String idempotencyKey;
    @Column(name = "model_name", length = 100)
    private String modelName;
    @Column(name = "error_message", length = 500)
    private String errorMessage;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected ArtifactReviewPreviewEntity() { }

    public ArtifactReviewPreviewEntity(
            String id, String artifactId, String ownerId, String runnerExecutionId,
            String runnerTemplateType, String runnerSummary, String manifestJson,
            String excludedPathsJson, String snapshotDigest, String idempotencyKey,
            Instant expiresAt, Instant now
    ) {
        this.id = id;
        this.artifactId = artifactId;
        this.ownerId = ownerId;
        this.runnerExecutionId = runnerExecutionId;
        this.runnerTemplateType = runnerTemplateType;
        this.runnerSummary = runnerSummary;
        this.manifestJson = manifestJson;
        this.excludedPathsJson = excludedPathsJson;
        this.snapshotDigest = snapshotDigest;
        this.status = ArtifactReviewPreviewStatus.WAITING_CONFIRMATION;
        this.idempotencyKey = idempotencyKey;
        this.expiresAt = expiresAt;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void reviewing(Instant now) {
        this.status = ArtifactReviewPreviewStatus.REVIEWING;
        this.updatedAt = now;
    }

    public void complete(String modelName, Instant now) {
        this.status = ArtifactReviewPreviewStatus.COMPLETED;
        this.modelName = modelName;
        this.updatedAt = now;
    }

    public void fail(String message, Instant now) {
        this.status = ArtifactReviewPreviewStatus.FAILED;
        this.errorMessage = message;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getArtifactId() { return artifactId; }
    public String getOwnerId() { return ownerId; }
    public String getRunnerExecutionId() { return runnerExecutionId; }
    public String getRunnerTemplateType() { return runnerTemplateType; }
    public String getRunnerSummary() { return runnerSummary; }
    public String getManifestJson() { return manifestJson; }
    public String getExcludedPathsJson() { return excludedPathsJson; }
    public String getSnapshotDigest() { return snapshotDigest; }
    public ArtifactReviewPreviewStatus getStatus() { return status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getModelName() { return modelName; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
