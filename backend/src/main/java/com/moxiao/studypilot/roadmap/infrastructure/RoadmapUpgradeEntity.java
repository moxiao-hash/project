package com.moxiao.studypilot.roadmap.infrastructure;

import com.moxiao.studypilot.roadmap.domain.UpgradeStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "roadmap_upgrades")
public class RoadmapUpgradeEntity {

    @Id
    @Column(nullable = false, length = 36)
    private String id;

    @Column(name = "owner_id", nullable = false, length = 36)
    private String ownerId;

    @Column(name = "user_roadmap_id", nullable = false, length = 36)
    private String userRoadmapId;

    @Column(name = "target_template_id", nullable = false, length = 36)
    private String targetTemplateId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UpgradeStatus status;

    @Column(name = "diff_json", nullable = false, columnDefinition = "LONGTEXT")
    private String diffJson;

    @Column(name = "idempotency_key", nullable = false, length = 180)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected RoadmapUpgradeEntity() {
    }

    public RoadmapUpgradeEntity(
            String id,
            String ownerId,
            String userRoadmapId,
            String targetTemplateId,
            UpgradeStatus status,
            String diffJson,
            String idempotencyKey,
            Instant createdAt,
            Instant completedAt
    ) {
        this.id = id;
        this.ownerId = ownerId;
        this.userRoadmapId = userRoadmapId;
        this.targetTemplateId = targetTemplateId;
        this.status = status;
        this.diffJson = diffJson;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = createdAt;
        this.completedAt = completedAt;
    }

    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getUserRoadmapId() { return userRoadmapId; }
    public String getTargetTemplateId() { return targetTemplateId; }
    public UpgradeStatus getStatus() { return status; }
    public String getDiffJson() { return diffJson; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
}
