package com.moxiao.studypilot.roadmap.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "roadmap_modules")
public class RoadmapModuleEntity {

    @Id
    @Column(nullable = false, length = 100)
    private String id;

    @Column(name = "template_id", nullable = false, length = 36)
    private String templateId;

    @Column(name = "stage_id", nullable = false, length = 80)
    private String stageId;

    @Column(name = "module_code", nullable = false, length = 100)
    private String moduleCode;

    @Column(name = "module_order", nullable = false)
    private int moduleOrder;

    @Column(nullable = false, length = 180)
    private String title;

    @Column(nullable = false, length = 2000)
    private String description;

    protected RoadmapModuleEntity() {
    }

    public RoadmapModuleEntity(
            String id,
            String templateId,
            String stageId,
            String moduleCode,
            int moduleOrder,
            String title,
            String description
    ) {
        this.id = id;
        this.templateId = templateId;
        this.stageId = stageId;
        this.moduleCode = moduleCode;
        this.moduleOrder = moduleOrder;
        this.title = title;
        this.description = description;
    }

    public String getId() { return id; }
    public String getTemplateId() { return templateId; }
    public String getStageId() { return stageId; }
    public String getModuleCode() { return moduleCode; }
    public int getModuleOrder() { return moduleOrder; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
}
