package com.moxiao.studypilot.roadmap.infrastructure;

import com.moxiao.studypilot.roadmap.domain.ArtifactStatus;
import com.moxiao.studypilot.roadmap.domain.AvailabilityStatus;
import com.moxiao.studypilot.roadmap.domain.CheckInStatus;
import com.moxiao.studypilot.roadmap.domain.CompletionStatus;
import com.moxiao.studypilot.roadmap.domain.LearningStatus;
import com.moxiao.studypilot.roadmap.domain.QuizStatus;
import com.moxiao.studypilot.roadmap.domain.UserRoadmapStatus;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoadmapEntityMappingTest {

    @Test
    void mapsRoadmapEntitiesToDedicatedTables() {
        assertTableName(RoadmapTemplateEntity.class, "roadmap_templates");
        assertTableName(RoadmapStageEntity.class, "roadmap_stages");
        assertTableName(RoadmapNodeEntity.class, "roadmap_nodes");
        assertTableName(UserRoadmapEntity.class, "user_roadmaps");
        assertTableName(UserRoadmapNodeEntity.class, "user_roadmap_nodes");
    }

    @Test
    void mutableUserRoadmapEntitiesUseOptimisticLocking() {
        assertThat(hasVersionField(UserRoadmapEntity.class)).isTrue();
        assertThat(hasVersionField(UserRoadmapNodeEntity.class)).isTrue();
    }

    @Test
    void initializesUserRoadmapNodeWithOrthogonalDefaults() {
        Instant now = Instant.parse("2026-08-09T00:00:00Z");

        UserRoadmapNodeEntity requiredArtifact = new UserRoadmapNodeEntity(
                "user-node-1", "roadmap-1", "node-1", "owner-1", "template-1",
                AvailabilityStatus.AVAILABLE, true, now
        );
        UserRoadmapNodeEntity optionalArtifact = new UserRoadmapNodeEntity(
                "user-node-2", "roadmap-1", "node-2", "owner-1", "template-1",
                AvailabilityStatus.LOCKED, false, now
        );

        assertThat(requiredArtifact.getOwnerId()).isEqualTo("owner-1");
        assertThat(requiredArtifact.getTemplateId()).isEqualTo("template-1");
        assertThat(requiredArtifact.getAvailabilityStatus()).isEqualTo(AvailabilityStatus.AVAILABLE);
        assertThat(requiredArtifact.getLearningStatus()).isEqualTo(LearningStatus.NOT_STARTED);
        assertThat(requiredArtifact.getCheckInStatus()).isEqualTo(CheckInStatus.MISSING);
        assertThat(requiredArtifact.getQuizStatus()).isEqualTo(QuizStatus.NOT_GENERATED);
        assertThat(requiredArtifact.getArtifactStatus()).isEqualTo(ArtifactStatus.MISSING);
        assertThat(requiredArtifact.getCompletionStatus()).isEqualTo(CompletionStatus.INCOMPLETE);
        assertThat(requiredArtifact.getCompletedAt()).isNull();
        assertThat(requiredArtifact.getUpdatedAt()).isEqualTo(now);
        assertThat(optionalArtifact.getArtifactStatus()).isEqualTo(ArtifactStatus.NOT_REQUIRED);
    }

    @Test
    void passingQuizDoesNotCompleteMilestoneUntilArtifactIsAccepted() {
        Instant now = Instant.parse("2026-08-31T00:00:00Z");
        UserRoadmapNodeEntity milestone = new UserRoadmapNodeEntity(
                "state-1", "roadmap-1", "node-1", "owner-1", "template-1",
                AvailabilityStatus.AVAILABLE, true, now
        );

        milestone.submitCheckInAndQueueQuiz(now.plusSeconds(1));
        milestone.markQuizReady(now.plusSeconds(2));
        milestone.recordQuizResult(80, now.plusSeconds(3));

        assertThat(milestone.getQuizStatus()).isEqualTo(QuizStatus.PASSED);
        assertThat(milestone.getArtifactStatus()).isEqualTo(ArtifactStatus.MISSING);
        assertThat(milestone.completionRequirementsSatisfied()).isFalse();
        assertThat(milestone.getCompletionStatus()).isEqualTo(CompletionStatus.INCOMPLETE);
    }

    @Test
    void createsOnlyActiveCurrentUserRoadmapAndSupersedesOnce() {
        Instant enrolledAt = Instant.parse("2026-08-09T00:00:00Z");
        Instant supersededAt = enrolledAt.plusSeconds(60);
        UserRoadmapEntity roadmap = new UserRoadmapEntity(
                "roadmap-1", "owner-1", "template-1", enrolledAt
        );

        assertThat(roadmap.getStatus()).isEqualTo(UserRoadmapStatus.ACTIVE);
        assertThat(roadmap.getActiveSlot()).isEqualTo("CURRENT");

        roadmap.supersede(supersededAt);

        assertThat(roadmap.getStatus()).isEqualTo(UserRoadmapStatus.SUPERSEDED);
        assertThat(roadmap.getActiveSlot()).isNull();
        assertThat(roadmap.getUpdatedAt()).isEqualTo(supersededAt);
        assertThatThrownBy(() -> roadmap.supersede(supersededAt.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMissingRequiredUserRoadmapValues() {
        Instant now = Instant.parse("2026-08-09T00:00:00Z");

        assertThatNullPointerException().isThrownBy(
                () -> new UserRoadmapEntity(null, "owner-1", "template-1", now)
        );
        assertThatNullPointerException().isThrownBy(
                () -> new UserRoadmapEntity("roadmap-1", null, "template-1", now)
        );
        assertThatNullPointerException().isThrownBy(
                () -> new UserRoadmapEntity("roadmap-1", "owner-1", null, now)
        );
        assertThatNullPointerException().isThrownBy(
                () -> new UserRoadmapEntity("roadmap-1", "owner-1", "template-1", null)
        );
        UserRoadmapEntity roadmap = new UserRoadmapEntity(
                "roadmap-1", "owner-1", "template-1", now
        );
        assertThatNullPointerException().isThrownBy(() -> roadmap.supersede(null));
    }

    private void assertTableName(Class<?> entityType, String tableName) {
        assertThat(entityType.getAnnotation(Table.class))
                .isNotNull()
                .extracting(Table::name)
                .isEqualTo(tableName);
    }

    private boolean hasVersionField(Class<?> entityType) {
        return Arrays.stream(entityType.getDeclaredFields())
                .map(Field::getDeclaredAnnotations)
                .flatMap(Arrays::stream)
                .anyMatch(annotation -> annotation.annotationType().equals(Version.class));
    }
}
