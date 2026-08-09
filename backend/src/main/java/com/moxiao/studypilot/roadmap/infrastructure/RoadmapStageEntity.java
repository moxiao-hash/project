package com.moxiao.studypilot.roadmap.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "roadmap_stages")
public class RoadmapStageEntity {

    @Id
    @Column(nullable = false, length = 80)
    private String id;

    @Column(name = "template_id", nullable = false, length = 36)
    private String templateId;

    @Column(name = "stage_code", nullable = false, length = 80)
    private String stageCode;

    @Column(name = "stage_order", nullable = false)
    private int stageOrder;

    @Column(nullable = false, length = 180)
    private String title;

    @Column(nullable = false, length = 2000)
    private String description;

    @Column(name = "graduation_project_title", nullable = false, length = 240)
    private String graduationProjectTitle;

    protected RoadmapStageEntity() {
    }

    public RoadmapStageEntity(
            String id,
            String templateId,
            String stageCode,
            int stageOrder,
            String title,
            String description,
            String graduationProjectTitle
    ) {
        this.id = id;
        this.templateId = templateId;
        this.stageCode = stageCode;
        this.stageOrder = stageOrder;
        this.title = title;
        this.description = description;
        this.graduationProjectTitle = graduationProjectTitle;
    }

    public String getId() { return id; }
    public String getTemplateId() { return templateId; }
    public String getStageCode() { return stageCode; }
    public int getStageOrder() { return stageOrder; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getGraduationProjectTitle() { return graduationProjectTitle; }
}
