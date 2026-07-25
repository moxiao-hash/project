package com.moxiao.studypilot.assessment.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "quizzes")
public class QuizEntity {

    @Id
    private String id;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(name = "material_id")
    private String materialId;

    @Column(nullable = false, length = 160)
    private String title;

    @Column(name = "model_name", nullable = false, length = 100)
    private String modelName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected QuizEntity() {
    }

    public QuizEntity(
            String id,
            String ownerId,
            String materialId,
            String title,
            String modelName,
            Instant createdAt
    ) {
        this.id = id;
        this.ownerId = ownerId;
        this.materialId = materialId;
        this.title = title;
        this.modelName = modelName;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getMaterialId() {
        return materialId;
    }

    public String getTitle() {
        return title;
    }

    public String getModelName() {
        return modelName;
    }
}
