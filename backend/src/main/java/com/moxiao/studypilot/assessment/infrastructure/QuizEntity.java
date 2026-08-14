package com.moxiao.studypilot.assessment.infrastructure;

import com.moxiao.studypilot.roadmap.domain.RoadmapQuizPurpose;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    @Column(name = "task_id")
    private String taskId;

    @Column(name = "lesson_id")
    private String lessonId;

    @Column(name = "roadmap_node_id", length = 100)
    private String roadmapNodeId;

    @Column(name = "user_roadmap_id", length = 36)
    private String userRoadmapId;

    @Column(name = "roadmap_stage_id", length = 80)
    private String roadmapStageId;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private RoadmapQuizPurpose purpose;

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
        this(id, ownerId, materialId, null, null, title, modelName, createdAt);
    }

    public QuizEntity(
            String id,
            String ownerId,
            String materialId,
            String taskId,
            String lessonId,
            String title,
            String modelName,
            Instant createdAt
    ) {
        this(id, ownerId, materialId, taskId, lessonId, null, null, null, null,
                title, modelName, createdAt);
    }

    public QuizEntity(
            String id, String ownerId, String materialId, String taskId, String lessonId,
            String roadmapNodeId, String userRoadmapId, String roadmapStageId,
            RoadmapQuizPurpose purpose, String title,
            String modelName, Instant createdAt
    ) {
        this.id = id;
        this.ownerId = ownerId;
        this.materialId = materialId;
        this.taskId = taskId;
        this.lessonId = lessonId;
        this.roadmapNodeId = roadmapNodeId;
        this.userRoadmapId = userRoadmapId;
        this.roadmapStageId = roadmapStageId;
        this.purpose = purpose;
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

    public String getTaskId() {
        return taskId;
    }

    public String getLessonId() {
        return lessonId;
    }

    public String getRoadmapNodeId() { return roadmapNodeId; }
    public String getUserRoadmapId() { return userRoadmapId; }
    public String getRoadmapStageId() { return roadmapStageId; }
    public RoadmapQuizPurpose getPurpose() { return purpose; }

    public String getTitle() {
        return title;
    }

    public String getModelName() {
        return modelName;
    }
}
