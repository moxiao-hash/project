package com.moxiao.studypilot.course.infrastructure;

import com.moxiao.studypilot.course.domain.CoursePublicationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "courses")
public class CourseEntity {

    @Id
    private String id;

    @Column(nullable = false, unique = true, length = 120)
    private String slug;

    @Column(nullable = false, length = 180)
    private String title;

    @Column(nullable = false, length = 2000)
    private String description;

    @Column(name = "tech_stack", nullable = false, length = 500)
    private String techStack;

    @Enumerated(EnumType.STRING)
    @Column(name = "publication_status", nullable = false, length = 20)
    private CoursePublicationStatus publicationStatus;

    @Column(nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CourseEntity() {
    }

    public CourseEntity(
            String id,
            String slug,
            String title,
            String description,
            String techStack,
            CoursePublicationStatus publicationStatus,
            int version,
            Instant now
    ) {
        this.id = id;
        this.slug = slug;
        this.title = title;
        this.description = description;
        this.techStack = techStack;
        this.publicationStatus = publicationStatus;
        this.version = version;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getSlug() { return slug; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getTechStack() { return techStack; }
    public CoursePublicationStatus getPublicationStatus() { return publicationStatus; }
    public int getVersion() { return version; }
}
