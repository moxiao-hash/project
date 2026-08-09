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
