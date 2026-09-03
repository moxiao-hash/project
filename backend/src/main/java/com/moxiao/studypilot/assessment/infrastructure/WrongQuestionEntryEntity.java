package com.moxiao.studypilot.assessment.infrastructure;

import com.moxiao.studypilot.assessment.domain.WrongQuestionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "wrong_question_entries", uniqueConstraints = @UniqueConstraint(
        name = "uk_wrong_question_owner_source", columnNames = {"owner_id", "source_question_id"}
))
public class WrongQuestionEntryEntity {
    @Id
    private String id;
    @Column(name = "owner_id", nullable = false, length = 36)
    private String ownerId;
    @Column(name = "source_question_id", nullable = false, length = 36)
    private String sourceQuestionId;
    @Column(name = "source_quiz_id", nullable = false, length = 36)
    private String sourceQuizId;
    @Column(name = "chapter_key", nullable = false, length = 180)
    private String chapterKey;
    @Column(name = "chapter_title", nullable = false, length = 240)
    private String chapterTitle;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WrongQuestionStatus status;
    @Column(name = "wrong_count", nullable = false)
    private int wrongCount;
    @Column(name = "redo_count", nullable = false)
    private int redoCount;
    @Column(name = "latest_answer_json", nullable = false, columnDefinition = "TEXT")
    private String latestAnswerJson;
    @Column(name = "first_wrong_at", nullable = false)
    private Instant firstWrongAt;
    @Column(name = "last_wrong_at", nullable = false)
    private Instant lastWrongAt;
    @Column(name = "mastered_at")
    private Instant masteredAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WrongQuestionEntryEntity() {
    }

    public WrongQuestionEntryEntity(
            String id, String ownerId, String sourceQuestionId, String sourceQuizId,
            String chapterKey, String chapterTitle, String latestAnswerJson, Instant now
    ) {
        this.id = id;
        this.ownerId = ownerId;
        this.sourceQuestionId = sourceQuestionId;
        this.sourceQuizId = sourceQuizId;
        this.chapterKey = chapterKey;
        this.chapterTitle = chapterTitle;
        this.status = WrongQuestionStatus.ACTIVE;
        this.wrongCount = 1;
        this.redoCount = 0;
        this.latestAnswerJson = latestAnswerJson;
        this.firstWrongAt = now;
        this.lastWrongAt = now;
        this.updatedAt = now;
    }

    public void recordWrong(String answerJson, boolean redo, Instant now) {
        status = WrongQuestionStatus.ACTIVE;
        wrongCount++;
        if (redo) redoCount++;
        latestAnswerJson = answerJson;
        lastWrongAt = now;
        masteredAt = null;
        updatedAt = now;
    }

    public void recordCorrect(boolean redo, Instant now) {
        status = WrongQuestionStatus.MASTERED;
        if (redo) redoCount++;
        masteredAt = now;
        updatedAt = now;
    }

    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getSourceQuestionId() { return sourceQuestionId; }
    public String getSourceQuizId() { return sourceQuizId; }
    public String getChapterKey() { return chapterKey; }
    public String getChapterTitle() { return chapterTitle; }
    public WrongQuestionStatus getStatus() { return status; }
    public int getWrongCount() { return wrongCount; }
    public int getRedoCount() { return redoCount; }
    public String getLatestAnswerJson() { return latestAnswerJson; }
    public Instant getFirstWrongAt() { return firstWrongAt; }
    public Instant getLastWrongAt() { return lastWrongAt; }
    public Instant getMasteredAt() { return masteredAt; }
}
