package com.moxiao.studypilot.roadmap.infrastructure;

import com.moxiao.studypilot.roadmap.domain.ArtifactStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "roadmap_artifact_reviews")
public class RoadmapArtifactReviewEntity {
    @Id
    @Column(nullable = false, length = 36)
    private String id;
    @Column(name = "artifact_id", nullable = false, length = 36)
    private String artifactId;
    @Column(name = "owner_id", nullable = false, length = 36)
    private String ownerId;
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", nullable = false, length = 20)
    private ArtifactStatus fromStatus;
    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private ArtifactStatus toStatus;
    @Column(name = "event_type", nullable = false, length = 30)
    private String eventType;
    @Column(nullable = false, length = 2000)
    private String details;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RoadmapArtifactReviewEntity() { }

    public RoadmapArtifactReviewEntity(
            String id, String artifactId, String ownerId, ArtifactStatus fromStatus,
            ArtifactStatus toStatus, String eventType, String details, Instant createdAt
    ) {
        this.id = id;
        this.artifactId = artifactId;
        this.ownerId = ownerId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.eventType = eventType;
        this.details = details;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getArtifactId() { return artifactId; }
    public String getOwnerId() { return ownerId; }
    public ArtifactStatus getFromStatus() { return fromStatus; }
    public ArtifactStatus getToStatus() { return toStatus; }
    public String getEventType() { return eventType; }
    public String getDetails() { return details; }
    public Instant getCreatedAt() { return createdAt; }
}
