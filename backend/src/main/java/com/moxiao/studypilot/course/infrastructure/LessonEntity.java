package com.moxiao.studypilot.course.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "lessons")
public class LessonEntity {

    @Id
    private String id;

    @Column(name = "module_id", nullable = false, length = 36)
    private String moduleId;

    @Column(name = "lesson_order", nullable = false)
    private int lessonOrder;

    @Column(nullable = false, unique = true, length = 140)
    private String slug;

    @Column(nullable = false, length = 180)
    private String title;

    @Column(nullable = false, length = 2000)
    private String summary;

    @Column(name = "estimated_minutes", nullable = false)
    private int estimatedMinutes;

    @Column(name = "content_json", nullable = false, columnDefinition = "LONGTEXT")
    private String contentJson;

    @Column(nullable = false)
    private boolean published;

    protected LessonEntity() {
    }

    public LessonEntity(
            String id,
            String moduleId,
            int lessonOrder,
            String slug,
            String title,
            String summary,
            int estimatedMinutes,
            String contentJson,
            boolean published
    ) {
        this.id = id;
        this.moduleId = moduleId;
        this.lessonOrder = lessonOrder;
        this.slug = slug;
        this.title = title;
        this.summary = summary;
        this.estimatedMinutes = estimatedMinutes;
        this.contentJson = contentJson;
        this.published = published;
    }

    public String getId() { return id; }
    public String getModuleId() { return moduleId; }
    public int getLessonOrder() { return lessonOrder; }
    public String getSlug() { return slug; }
    public String getTitle() { return title; }
    public String getSummary() { return summary; }
    public int getEstimatedMinutes() { return estimatedMinutes; }
    public String getContentJson() { return contentJson; }
    public boolean isPublished() { return published; }
}
