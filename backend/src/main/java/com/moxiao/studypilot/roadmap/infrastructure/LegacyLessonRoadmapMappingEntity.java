package com.moxiao.studypilot.roadmap.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "legacy_lesson_roadmap_mappings")
@IdClass(LegacyLessonRoadmapMappingEntity.Key.class)
public class LegacyLessonRoadmapMappingEntity {

    @Id
    @Column(name = "lesson_id", nullable = false, length = 80)
    private String lessonId;

    @Id
    @Column(name = "template_id", nullable = false, length = 36)
    private String templateId;

    @Column(name = "node_id", nullable = false, length = 100)
    private String nodeId;

    protected LegacyLessonRoadmapMappingEntity() {
    }

    public LegacyLessonRoadmapMappingEntity(String lessonId, String templateId, String nodeId) {
        this.lessonId = lessonId;
        this.templateId = templateId;
        this.nodeId = nodeId;
    }

    public String getLessonId() { return lessonId; }
    public String getTemplateId() { return templateId; }
    public String getNodeId() { return nodeId; }

    public static class Key implements Serializable {
        private String lessonId;
        private String templateId;

        public Key() {
        }

        public Key(String lessonId, String templateId) {
            this.lessonId = lessonId;
            this.templateId = templateId;
        }

        @Override
        public boolean equals(Object candidate) {
            if (this == candidate) {
                return true;
            }
            if (!(candidate instanceof Key key)) {
                return false;
            }
            return Objects.equals(lessonId, key.lessonId)
                    && Objects.equals(templateId, key.templateId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(lessonId, templateId);
        }
    }
}
