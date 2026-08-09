package com.moxiao.studypilot.roadmap.infrastructure;

import com.moxiao.studypilot.roadmap.domain.RoadmapPublicationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "roadmap_templates")
public class RoadmapTemplateEntity {

    @Id
    @Column(nullable = false, length = 36)
    private String id;

    @Column(name = "roadmap_code", nullable = false, length = 80)
    private String roadmapCode;

    @Column(name = "template_version", nullable = false)
    private int templateVersion;

    @Column(nullable = false, length = 180)
    private String title;

    @Column(nullable = false, length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "publication_status", nullable = false, length = 20)
    private RoadmapPublicationStatus publicationStatus;

    @Column(name = "content_checksum", nullable = false, length = 64)
    private String contentChecksum;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RoadmapTemplateEntity() {
    }

    public RoadmapTemplateEntity(
            String id,
            String roadmapCode,
            int templateVersion,
            String title,
            String description,
            RoadmapPublicationStatus publicationStatus,
            String contentChecksum,
            Instant now
    ) {
        this.id = id;
        this.roadmapCode = roadmapCode;
        this.templateVersion = templateVersion;
        this.title = title;
        this.description = description;
        this.publicationStatus = publicationStatus;
        this.contentChecksum = contentChecksum;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getRoadmapCode() { return roadmapCode; }
    public int getTemplateVersion() { return templateVersion; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public RoadmapPublicationStatus getPublicationStatus() { return publicationStatus; }
    public String getContentChecksum() { return contentChecksum; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
