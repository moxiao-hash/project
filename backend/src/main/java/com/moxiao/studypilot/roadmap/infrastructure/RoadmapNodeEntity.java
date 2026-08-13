package com.moxiao.studypilot.roadmap.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Objects;

@Entity
@Table(name = "roadmap_nodes")
public class RoadmapNodeEntity {

    @Id
    @Column(nullable = false, length = 100)
    private String id;

    @Column(name = "template_id", nullable = false, length = 36)
    private String templateId;

    @Column(name = "stage_id", nullable = false, length = 80)
    private String stageId;

    @Column(name = "module_id", length = 100)
    private String moduleId;

    @Column(name = "node_code", nullable = false, length = 100)
    private String nodeCode;

    @Column(name = "node_order", nullable = false)
    private int nodeOrder;

    @Column(nullable = false, length = 180)
    private String title;

    @Column(name = "objectives_json", nullable = false, columnDefinition = "LONGTEXT")
    private String objectivesJson;

    @Column(name = "high_frequency_json", nullable = false, columnDefinition = "LONGTEXT")
    private String highFrequencyJson;

    @Column(name = "common_mistakes_json", nullable = false, columnDefinition = "LONGTEXT")
    private String commonMistakesJson;

    @Column(name = "search_keywords_json", nullable = false, columnDefinition = "LONGTEXT")
    private String searchKeywordsJson;

    @Column(name = "artifact_requirement_json", nullable = false, columnDefinition = "LONGTEXT")
    private String artifactRequirementJson;

    @Column(name = "quiz_blueprint_json", nullable = false, columnDefinition = "LONGTEXT")
    private String quizBlueprintJson;

    @Column(name = "estimated_minutes", nullable = false)
    private int estimatedMinutes;

    @Column(name = "practice_minutes", nullable = false)
    private int practiceMinutes;

    @Column(nullable = false, length = 20)
    private String difficulty;

    @Column(name = "required_node", nullable = false)
    private boolean requiredNode;

    protected RoadmapNodeEntity() {
    }

    public RoadmapNodeEntity(
            String id,
            String templateId,
            String stageId,
            String nodeCode,
            int nodeOrder,
            String title,
            String objectivesJson,
            String highFrequencyJson,
            String commonMistakesJson,
            String searchKeywordsJson,
            String artifactRequirementJson,
            String quizBlueprintJson,
            int estimatedMinutes,
            int practiceMinutes,
            String difficulty,
            boolean requiredNode
    ) {
        this.id = id;
        this.templateId = templateId;
        this.stageId = stageId;
        this.nodeCode = nodeCode;
        this.nodeOrder = nodeOrder;
        this.title = title;
        this.objectivesJson = objectivesJson;
        this.highFrequencyJson = highFrequencyJson;
        this.commonMistakesJson = commonMistakesJson;
        this.searchKeywordsJson = searchKeywordsJson;
        this.artifactRequirementJson = artifactRequirementJson;
        this.quizBlueprintJson = quizBlueprintJson;
        this.estimatedMinutes = estimatedMinutes;
        this.practiceMinutes = practiceMinutes;
        this.difficulty = difficulty;
        this.requiredNode = requiredNode;
    }

    public RoadmapNodeEntity(
            String id,
            String templateId,
            String stageId,
            String moduleId,
            String nodeCode,
            int nodeOrder,
            String title,
            String objectivesJson,
            String highFrequencyJson,
            String commonMistakesJson,
            String searchKeywordsJson,
            String artifactRequirementJson,
            String quizBlueprintJson,
            int estimatedMinutes,
            int practiceMinutes,
            String difficulty,
            boolean requiredNode
    ) {
        this(
                id, templateId, stageId, nodeCode, nodeOrder, title, objectivesJson,
                highFrequencyJson, commonMistakesJson, searchKeywordsJson,
                artifactRequirementJson, quizBlueprintJson, estimatedMinutes, practiceMinutes,
                difficulty, requiredNode
        );
        this.moduleId = Objects.requireNonNull(moduleId, "moduleId");
    }

    public String getId() { return id; }
    public String getTemplateId() { return templateId; }
    public String getStageId() { return stageId; }
    public String getModuleId() { return moduleId; }
    public String getNodeCode() { return nodeCode; }
    public int getNodeOrder() { return nodeOrder; }
    public String getTitle() { return title; }
    public String getObjectivesJson() { return objectivesJson; }
    public String getHighFrequencyJson() { return highFrequencyJson; }
    public String getCommonMistakesJson() { return commonMistakesJson; }
    public String getSearchKeywordsJson() { return searchKeywordsJson; }
    public String getArtifactRequirementJson() { return artifactRequirementJson; }
    public String getQuizBlueprintJson() { return quizBlueprintJson; }
    public int getEstimatedMinutes() { return estimatedMinutes; }
    public int getPracticeMinutes() { return practiceMinutes; }
    public String getDifficulty() { return difficulty; }
    public boolean isRequiredNode() { return requiredNode; }
}
