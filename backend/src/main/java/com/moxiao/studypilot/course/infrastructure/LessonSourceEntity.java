package com.moxiao.studypilot.course.infrastructure;

import com.moxiao.studypilot.course.domain.LessonSourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "lesson_sources")
public class LessonSourceEntity {

    @Id
    private String id;

    @Column(name = "lesson_id", nullable = false, length = 80)
    private String lessonId;

    @Column(name = "source_order", nullable = false)
    private int sourceOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private LessonSourceType sourceType;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(nullable = false, length = 2048)
    private String url;

    @Column(length = 300)
    private String locator;

    @Column(length = 20)
    private String bvid;

    @Column(name = "video_page")
    private Integer videoPage;

    protected LessonSourceEntity() {
    }

    public LessonSourceEntity(
            String id,
            String lessonId,
            int sourceOrder,
            LessonSourceType sourceType,
            String title,
            String url,
            String locator,
            String bvid,
            Integer videoPage
    ) {
        this.id = id;
        this.lessonId = lessonId;
        this.sourceOrder = sourceOrder;
        this.sourceType = sourceType;
        this.title = title;
        this.url = url;
        this.locator = locator;
        this.bvid = bvid;
        this.videoPage = videoPage;
    }

    public String getId() { return id; }
    public String getLessonId() { return lessonId; }
    public int getSourceOrder() { return sourceOrder; }
    public LessonSourceType getSourceType() { return sourceType; }
    public String getTitle() { return title; }
    public String getUrl() { return url; }
    public String getLocator() { return locator; }
    public String getBvid() { return bvid; }
    public Integer getVideoPage() { return videoPage; }
}
