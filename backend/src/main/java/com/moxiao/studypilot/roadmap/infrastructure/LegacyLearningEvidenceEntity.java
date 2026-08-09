package com.moxiao.studypilot.roadmap.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "legacy_learning_evidence")
public class LegacyLearningEvidenceEntity {

    @Id
    @Column(nullable = false, length = 36)
    private String id;

    @Column(name = "owner_id", nullable = false, length = 36)
    private String ownerId;

    @Column(name = "user_roadmap_node_id", nullable = false, length = 36)
    private String userRoadmapNodeId;

    @Column(name = "lesson_id", nullable = false, length = 80)
    private String lessonId;

    @Column(name = "original_status", nullable = false, length = 20)
    private String originalStatus;

    @Column(name = "evidence_json", nullable = false, columnDefinition = "LONGTEXT")
    private String evidenceJson;

    @Column(name = "migration_version", nullable = false)
    private int migrationVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LegacyLearningEvidenceEntity() {
    }

    public LegacyLearningEvidenceEntity(
            String id,
            String ownerId,
            String userRoadmapNodeId,
            String lessonId,
            String originalStatus,
            String evidenceJson,
            int migrationVersion,
            Instant createdAt
    ) {
        this.id = id;
        this.ownerId = ownerId;
        this.userRoadmapNodeId = userRoadmapNodeId;
        this.lessonId = lessonId;
        this.originalStatus = originalStatus;
        this.evidenceJson = evidenceJson;
        this.migrationVersion = migrationVersion;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getUserRoadmapNodeId() { return userRoadmapNodeId; }
    public String getLessonId() { return lessonId; }
    public String getOriginalStatus() { return originalStatus; }
    public String getEvidenceJson() { return evidenceJson; }
    public int getMigrationVersion() { return migrationVersion; }
    public Instant getCreatedAt() { return createdAt; }
}
