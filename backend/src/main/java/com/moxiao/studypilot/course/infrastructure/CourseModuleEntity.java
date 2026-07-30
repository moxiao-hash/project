package com.moxiao.studypilot.course.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "course_modules")
public class CourseModuleEntity {

    @Id
    private String id;

    @Column(name = "course_id", nullable = false, length = 36)
    private String courseId;

    @Column(name = "module_order", nullable = false)
    private int moduleOrder;

    @Column(nullable = false, length = 180)
    private String title;

    @Column(nullable = false, length = 1000)
    private String description;

    protected CourseModuleEntity() {
    }

    public CourseModuleEntity(
            String id,
            String courseId,
            int moduleOrder,
            String title,
            String description
    ) {
        this.id = id;
        this.courseId = courseId;
        this.moduleOrder = moduleOrder;
        this.title = title;
        this.description = description;
    }

    public String getId() { return id; }
    public String getCourseId() { return courseId; }
    public int getModuleOrder() { return moduleOrder; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
}
