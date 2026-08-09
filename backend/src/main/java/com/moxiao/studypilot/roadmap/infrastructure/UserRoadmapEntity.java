package com.moxiao.studypilot.roadmap.infrastructure;

import com.moxiao.studypilot.roadmap.domain.UserRoadmapStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "user_roadmaps")
public class UserRoadmapEntity {

    private static final String CURRENT_ACTIVE_SLOT = "CURRENT";

    @Id
    @Column(nullable = false, length = 36)
    private String id;

    @Column(name = "owner_id", nullable = false, length = 36)
    private String ownerId;

    @Column(name = "template_id", nullable = false, length = 36)
    private String templateId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRoadmapStatus status;

    @Column(name = "active_slot", length = 20)
    private String activeSlot;

    @Column(name = "enrolled_at", nullable = false)
    private Instant enrolledAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected UserRoadmapEntity() {
    }

    public UserRoadmapEntity(
            String id,
            String ownerId,
            String templateId,
            Instant now
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId must not be null");
        this.templateId = Objects.requireNonNull(templateId, "templateId must not be null");
        this.status = UserRoadmapStatus.ACTIVE;
        this.activeSlot = CURRENT_ACTIVE_SLOT;
        this.enrolledAt = Objects.requireNonNull(now, "now must not be null");
        this.updatedAt = now;
    }

    public void supersede(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (status != UserRoadmapStatus.ACTIVE) {
            throw new IllegalStateException("Only an active roadmap can be superseded");
        }
        this.status = UserRoadmapStatus.SUPERSEDED;
        this.activeSlot = null;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getTemplateId() { return templateId; }
    public UserRoadmapStatus getStatus() { return status; }
    public String getActiveSlot() { return activeSlot; }
    public Instant getEnrolledAt() { return enrolledAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getRowVersion() { return rowVersion; }
}
