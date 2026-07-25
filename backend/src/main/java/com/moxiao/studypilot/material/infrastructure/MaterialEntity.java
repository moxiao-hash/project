package com.moxiao.studypilot.material.infrastructure;

import com.moxiao.studypilot.material.domain.MaterialCategory;
import com.moxiao.studypilot.material.domain.MaterialProcessingStatus;
import com.moxiao.studypilot.material.domain.MaterialType;
import com.moxiao.studypilot.user.domain.PrivacyLevel;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "materials")
public class MaterialEntity {

    @Id
    private String id;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(nullable = false, length = 180)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "material_type", nullable = false, length = 30)
    private MaterialType materialType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MaterialCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "privacy_level", nullable = false, length = 20)
    private PrivacyLevel privacyLevel;

    @Column(name = "source_url", length = 2048)
    private String sourceUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 20)
    private MaterialProcessingStatus processingStatus;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "content_reference", length = 500)
    private String contentReference;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "material_tags", joinColumns = @JoinColumn(name = "material_id"))
    @OrderColumn(name = "position")
    @Column(name = "tag", nullable = false, length = 100)
    private List<String> tags = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "material_knowledge_points",
            joinColumns = @JoinColumn(name = "material_id")
    )
    @OrderColumn(name = "position")
    @Column(name = "knowledge_point", nullable = false, length = 180)
    private List<String> knowledgePoints = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MaterialEntity() {
    }

    public MaterialEntity(
            String id,
            String ownerId,
            String title,
            MaterialType materialType,
            MaterialCategory category,
            PrivacyLevel privacyLevel,
            String sourceUrl,
            Instant now
    ) {
        this.id = id;
        this.ownerId = ownerId;
        this.title = title;
        this.materialType = materialType;
        this.category = category;
        this.privacyLevel = privacyLevel;
        this.sourceUrl = sourceUrl;
        this.processingStatus = MaterialProcessingStatus.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void updateProcessingResult(
            MaterialProcessingStatus status,
            String summary,
            List<String> tags,
            List<String> knowledgePoints,
            String contentReference,
            String failureReason,
            Instant now
    ) {
        this.processingStatus = status;
        this.summary = summary;
        this.tags = new ArrayList<>(tags);
        this.knowledgePoints = new ArrayList<>(knowledgePoints);
        this.contentReference = contentReference;
        this.failureReason = failureReason;
        this.updatedAt = now;
    }

    public String getId() {
        return id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getTitle() {
        return title;
    }

    public MaterialType getMaterialType() {
        return materialType;
    }

    public MaterialCategory getCategory() {
        return category;
    }

    public PrivacyLevel getPrivacyLevel() {
        return privacyLevel;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public MaterialProcessingStatus getProcessingStatus() {
        return processingStatus;
    }

    public String getSummary() {
        return summary;
    }

    public String getContentReference() {
        return contentReference;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public List<String> getTags() {
        return List.copyOf(tags);
    }

    public List<String> getKnowledgePoints() {
        return List.copyOf(knowledgePoints);
    }
}
