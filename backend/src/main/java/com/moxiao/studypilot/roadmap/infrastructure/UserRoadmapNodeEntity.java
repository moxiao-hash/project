package com.moxiao.studypilot.roadmap.infrastructure;

import com.moxiao.studypilot.roadmap.domain.ArtifactStatus;
import com.moxiao.studypilot.roadmap.domain.AvailabilityStatus;
import com.moxiao.studypilot.roadmap.domain.CheckInStatus;
import com.moxiao.studypilot.roadmap.domain.CompletionStatus;
import com.moxiao.studypilot.roadmap.domain.LearningStatus;
import com.moxiao.studypilot.roadmap.domain.QuizStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "user_roadmap_nodes")
public class UserRoadmapNodeEntity {

    @Id
    @Column(nullable = false, length = 36)
    private String id;

    @Column(name = "user_roadmap_id", nullable = false, length = 36)
    private String userRoadmapId;

    @Column(name = "node_id", nullable = false, length = 100)
    private String nodeId;

    @Column(name = "owner_id", nullable = false, length = 36)
    private String ownerId;

    @Column(name = "template_id", nullable = false, length = 36)
    private String templateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "availability_status", nullable = false, length = 20)
    private AvailabilityStatus availabilityStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "learning_status", nullable = false, length = 20)
    private LearningStatus learningStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "check_in_status", nullable = false, length = 20)
    private CheckInStatus checkInStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "quiz_status", nullable = false, length = 30)
    private QuizStatus quizStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "artifact_status", nullable = false, length = 20)
    private ArtifactStatus artifactStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "completion_status", nullable = false, length = 20)
    private CompletionStatus completionStatus;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected UserRoadmapNodeEntity() {
    }

    public UserRoadmapNodeEntity(
            String id,
            String userRoadmapId,
            String nodeId,
            String ownerId,
            String templateId,
            AvailabilityStatus availabilityStatus,
            boolean artifactRequired,
            Instant now
    ) {
        this.id = id;
        this.userRoadmapId = userRoadmapId;
        this.nodeId = nodeId;
        this.ownerId = ownerId;
        this.templateId = templateId;
        this.availabilityStatus = availabilityStatus;
        this.learningStatus = LearningStatus.NOT_STARTED;
        this.checkInStatus = CheckInStatus.MISSING;
        this.quizStatus = QuizStatus.NOT_GENERATED;
        this.artifactStatus = artifactRequired ? ArtifactStatus.MISSING : ArtifactStatus.NOT_REQUIRED;
        this.completionStatus = CompletionStatus.INCOMPLETE;
        this.updatedAt = now;
    }

    public void changeAvailability(AvailabilityStatus availabilityStatus, Instant now) {
        Objects.requireNonNull(availabilityStatus, "availabilityStatus must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (this.availabilityStatus == availabilityStatus) {
            return;
        }
        this.availabilityStatus = availabilityStatus;
        this.updatedAt = now;
    }

    public void submitCheckInAndQueueQuiz(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (availabilityStatus != AvailabilityStatus.AVAILABLE) {
            throw new IllegalStateException("锁定节点不能提交打卡: " + nodeId);
        }
        checkInStatus = CheckInStatus.SUBMITTED;
        quizStatus = QuizStatus.GENERATING;
        if (learningStatus == LearningStatus.NOT_STARTED) {
            learningStatus = LearningStatus.IN_PROGRESS;
        }
        updatedAt = now;
    }

    public void markQuizGenerationFailed(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        quizStatus = QuizStatus.FAILED;
        updatedAt = now;
    }

    public void retryQuizGeneration(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (checkInStatus != CheckInStatus.SUBMITTED || quizStatus != QuizStatus.FAILED) {
            throw new IllegalStateException("路线节点测验当前不可重试: " + nodeId);
        }
        quizStatus = QuizStatus.GENERATING;
        updatedAt = now;
    }

    public void markQuizReady(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (checkInStatus != CheckInStatus.SUBMITTED) {
            throw new IllegalStateException("未打卡节点不能生成测验: " + nodeId);
        }
        quizStatus = QuizStatus.READY;
        updatedAt = now;
    }

    public void markQuizEvaluating(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (quizStatus != QuizStatus.PASSED) {
            quizStatus = QuizStatus.EVALUATING;
            updatedAt = now;
        }
    }

    public void recordQuizResult(double score, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (quizStatus == QuizStatus.PASSED) {
            return;
        }
        quizStatus = score >= 70 ? QuizStatus.PASSED : QuizStatus.FAILED;
        updatedAt = now;
    }

    void completeAfterRequirements(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (completionStatus == CompletionStatus.COMPLETED) {
            return;
        }
        if (availabilityStatus != AvailabilityStatus.AVAILABLE
                || checkInStatus != CheckInStatus.SUBMITTED
                || quizStatus != QuizStatus.PASSED
                || (artifactStatus != ArtifactStatus.NOT_REQUIRED
                && artifactStatus != ArtifactStatus.ACCEPTED)) {
            throw new IllegalStateException("路线节点尚未满足完成条件: " + nodeId);
        }
        this.completionStatus = CompletionStatus.COMPLETED;
        this.completedAt = now;
        this.updatedAt = now;
    }

    boolean completionRequirementsSatisfied() {
        return availabilityStatus == AvailabilityStatus.AVAILABLE
                && checkInStatus == CheckInStatus.SUBMITTED
                && quizStatus == QuizStatus.PASSED
                && (artifactStatus == ArtifactStatus.NOT_REQUIRED
                || artifactStatus == ArtifactStatus.ACCEPTED);
    }

    /** Restores verified completion evidence while moving between equivalent immutable nodes. */
    public void carryCompletedFromUpgrade(UserRoadmapNodeEntity source, Instant now) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (source.completionStatus != CompletionStatus.COMPLETED) {
            throw new IllegalArgumentException("Only completed source state can be carried");
        }
        this.availabilityStatus = AvailabilityStatus.AVAILABLE;
        this.learningStatus = source.learningStatus;
        this.checkInStatus = source.checkInStatus;
        this.quizStatus = source.quizStatus;
        this.artifactStatus = source.artifactStatus;
        this.completionStatus = CompletionStatus.COMPLETED;
        this.completedAt = source.completedAt == null ? now : source.completedAt;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getUserRoadmapId() { return userRoadmapId; }
    public String getNodeId() { return nodeId; }
    public String getOwnerId() { return ownerId; }
    public String getTemplateId() { return templateId; }
    public AvailabilityStatus getAvailabilityStatus() { return availabilityStatus; }
    public LearningStatus getLearningStatus() { return learningStatus; }
    public CheckInStatus getCheckInStatus() { return checkInStatus; }
    public QuizStatus getQuizStatus() { return quizStatus; }
    public ArtifactStatus getArtifactStatus() { return artifactStatus; }
    public CompletionStatus getCompletionStatus() { return completionStatus; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getRowVersion() { return rowVersion; }
}
