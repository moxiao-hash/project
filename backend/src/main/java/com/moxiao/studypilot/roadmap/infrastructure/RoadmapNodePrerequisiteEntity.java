package com.moxiao.studypilot.roadmap.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "roadmap_node_prerequisites")
public class RoadmapNodePrerequisiteEntity {

    @Id
    @Column(nullable = false, length = 36)
    private String id;

    @Column(name = "template_id", nullable = false, length = 36)
    private String templateId;

    @Column(name = "node_id", nullable = false, length = 100)
    private String nodeId;

    @Column(name = "prerequisite_node_id", nullable = false, length = 100)
    private String prerequisiteNodeId;

    protected RoadmapNodePrerequisiteEntity() {
    }

    public RoadmapNodePrerequisiteEntity(
            String id,
            String templateId,
            String nodeId,
            String prerequisiteNodeId
    ) {
        this.id = id;
        this.templateId = templateId;
        this.nodeId = nodeId;
        this.prerequisiteNodeId = prerequisiteNodeId;
    }

    public String getId() { return id; }
    public String getTemplateId() { return templateId; }
    public String getNodeId() { return nodeId; }
    public String getPrerequisiteNodeId() { return prerequisiteNodeId; }
}
