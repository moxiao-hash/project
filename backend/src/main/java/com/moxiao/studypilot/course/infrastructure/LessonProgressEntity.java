package com.moxiao.studypilot.course.infrastructure;

import com.moxiao.studypilot.course.domain.LessonProgressStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "lesson_progress")
public class LessonProgressEntity {

    @Id
    private String id;

    @Column(name = "owner_id", nullable = false, length = 36)
    private String ownerId;

    @Column(name = "lesson_id", nullable = false, length = 80)
    private String lessonId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LessonProgressStatus status;

    @Column(name = "video_completed", nullable = false)
    private boolean videoCompleted;

    @Column(name = "reading_completed", nullable = false)
    private boolean readingCompleted;

    @Column(name = "practice_completed", nullable = false)
    private boolean practiceCompleted;

    @Column(name = "checkpoint_passed", nullable = false)
    private boolean checkpointPassed;

    @Column(name = "quiz_passed", nullable = false)
    private boolean quizPassed;

    @Column(name = "last_section_key", length = 120)
    private String lastSectionKey;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LessonProgressEntity() {
    }

    public LessonProgressEntity(String id, String ownerId, String lessonId, Instant now) {
        this.id = id;
        this.ownerId = ownerId;
        this.lessonId = lessonId;
        this.status = LessonProgressStatus.NOT_STARTED;
        this.updatedAt = now;
    }

    public void updateLearningActivity(
            boolean videoCompleted,
            boolean readingCompleted,
            String lastSectionKey,
            Instant now
    ) {
        this.videoCompleted = videoCompleted;
        this.readingCompleted = readingCompleted;
        this.lastSectionKey = lastSectionKey;
        if (startedAt == null && (videoCompleted || readingCompleted)) {
            startedAt = now;
        }
        recalculate(now);
    }

    public void markCheckpointPassed(Instant now) {
        checkpointPassed = true;
        if (startedAt == null) {
            startedAt = now;
        }
        recalculate(now);
    }

    public void markQuizPassed(Instant now) {
        quizPassed = true;
        if (startedAt == null) {
            startedAt = now;
        }
        recalculate(now);
    }

    private void recalculate(Instant now) {
        practiceCompleted = checkpointPassed && quizPassed;
        if (videoCompleted && readingCompleted && practiceCompleted) {
            status = LessonProgressStatus.COMPLETED;
            completedAt = completedAt == null ? now : completedAt;
        } else if (videoCompleted || readingCompleted || practiceCompleted) {
            status = LessonProgressStatus.IN_PROGRESS;
            completedAt = null;
        } else {
            status = LessonProgressStatus.NOT_STARTED;
            completedAt = null;
        }
        updatedAt = now;
    }

    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getLessonId() { return lessonId; }
    public LessonProgressStatus getStatus() { return status; }
    public boolean isVideoCompleted() { return videoCompleted; }
    public boolean isReadingCompleted() { return readingCompleted; }
    public boolean isPracticeCompleted() { return practiceCompleted; }
    public boolean isCheckpointPassed() { return checkpointPassed; }
    public boolean isQuizPassed() { return quizPassed; }
    public String getLastSectionKey() { return lastSectionKey; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
