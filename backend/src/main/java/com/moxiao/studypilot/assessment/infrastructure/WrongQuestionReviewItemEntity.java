package com.moxiao.studypilot.assessment.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "wrong_question_review_items")
public class WrongQuestionReviewItemEntity {
    @Id private String id;
    @Column(name = "review_id", nullable = false, length = 36) private String reviewId;
    @Column(name = "entry_id", nullable = false, length = 36) private String entryId;
    @Column(name = "review_question_id", nullable = false, unique = true, length = 36)
    private String reviewQuestionId;

    protected WrongQuestionReviewItemEntity() {
    }

    public WrongQuestionReviewItemEntity(
            String id, String reviewId, String entryId, String reviewQuestionId
    ) {
        this.id = id;
        this.reviewId = reviewId;
        this.entryId = entryId;
        this.reviewQuestionId = reviewQuestionId;
    }

    public String getReviewId() { return reviewId; }
    public String getEntryId() { return entryId; }
    public String getReviewQuestionId() { return reviewQuestionId; }
}
