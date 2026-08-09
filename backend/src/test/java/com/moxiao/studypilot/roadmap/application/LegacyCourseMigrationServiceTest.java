package com.moxiao.studypilot.roadmap.application;

import com.moxiao.studypilot.auth.infrastructure.UserAccountEntity;
import com.moxiao.studypilot.auth.infrastructure.UserAccountJpaRepository;
import com.moxiao.studypilot.course.api.LessonResponse;
import com.moxiao.studypilot.course.application.CourseCatalogImporter;
import com.moxiao.studypilot.course.application.CourseLearningService;
import com.moxiao.studypilot.course.infrastructure.LessonProgressEntity;
import com.moxiao.studypilot.course.infrastructure.LessonProgressJpaRepository;
import com.moxiao.studypilot.roadmap.domain.ArtifactStatus;
import com.moxiao.studypilot.roadmap.domain.CheckInStatus;
import com.moxiao.studypilot.roadmap.domain.CompletionStatus;
import com.moxiao.studypilot.roadmap.domain.QuizStatus;
import com.moxiao.studypilot.roadmap.infrastructure.LegacyLearningEvidenceEntity;
import com.moxiao.studypilot.roadmap.infrastructure.LegacyLearningEvidenceJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.LegacyLessonRoadmapMappingJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodePrerequisiteJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapStageJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapTemplateJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapNodeEntity;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapNodeJpaRepository;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

@SpringBootTest
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class LegacyCourseMigrationServiceTest {

    private static final String MAPPED_LESSON = "lesson-rest-controller";
    private static final String TEMPLATE_ID = "studypilot-java-ai-v1";
    private static final String MAPPED_NODE_ID = TEMPLATE_ID + "-spring-mvc-rest";

    @Autowired LegacyCourseMigrationService service;
    @Autowired CourseCatalogImporter courseCatalogImporter;
    @Autowired RoadmapCatalogImporter roadmapCatalogImporter;
    @Autowired RoadmapEnrollmentService enrollmentService;
    @Autowired CourseLearningService courseLearningService;
    @Autowired UserAccountJpaRepository userRepository;
    @Autowired LessonProgressJpaRepository progressRepository;
    @Autowired LegacyLessonRoadmapMappingJpaRepository mappingRepository;
    @Autowired LegacyLearningEvidenceJpaRepository evidenceRepository;
    @Autowired UserRoadmapJpaRepository userRoadmapRepository;
    @Autowired UserRoadmapNodeJpaRepository userNodeRepository;
    @Autowired RoadmapNodePrerequisiteJpaRepository prerequisiteRepository;
    @Autowired RoadmapNodeJpaRepository nodeRepository;
    @Autowired RoadmapStageJpaRepository stageRepository;
    @Autowired RoadmapTemplateJpaRepository templateRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetAndImportCatalogs() {
        jdbcTemplate.execute("""
                ALTER TABLE legacy_learning_evidence
                ADD CONSTRAINT IF NOT EXISTS uk_legacy_evidence_migration
                UNIQUE (owner_id, lesson_id, migration_version)
                """);
        evidenceRepository.deleteAll();
        userNodeRepository.deleteAll();
        userRoadmapRepository.deleteAll();
        mappingRepository.deleteAll();
        progressRepository.deleteAll();
        userRepository.deleteAll();
        prerequisiteRepository.deleteAll();
        nodeRepository.deleteAll();
        stageRepository.deleteAll();
        templateRepository.deleteAll();
        courseCatalogImporter.importCatalog();
        roadmapCatalogImporter.importCatalog();
    }

    @Test
    void completedLessonBecomesExactLegacyEvidenceButDoesNotCompleteRoadmapNode() {
        String ownerId = createOwner("completed");
        String roadmapId = enroll(ownerId);
        Instant completedAt = Instant.parse("2026-08-09T03:04:05Z");
        completeLesson(ownerId, MAPPED_LESSON, completedAt);
        UserRoadmapNodeEntity before = mappedNode(roadmapId);
        List<Object> stateBefore = nodeStateSnapshot(before);

        service.migrateOwner(roadmapId, 1);
        service.migrateOwner(roadmapId, 1);

        LegacyLearningEvidenceEntity evidence = evidenceRepository.findAll().get(0);
        assertThat(evidenceRepository.count()).isEqualTo(1);
        assertThat(evidence.getOwnerId()).isEqualTo(ownerId);
        assertThat(evidence.getUserRoadmapNodeId()).isEqualTo(before.getId());
        assertThat(evidence.getLessonId()).isEqualTo(MAPPED_LESSON);
        assertThat(evidence.getOriginalStatus()).isEqualTo("COMPLETED");
        assertThat(evidence.getMigrationVersion()).isEqualTo(1);
        JsonNode payload = objectMapper.readTree(evidence.getEvidenceJson());
        assertThat(payload.path("type").asText()).isEqualTo("LEGACY_LESSON_PROGRESS");
        assertThat(payload.path("videoCompleted").asBoolean()).isTrue();
        assertThat(payload.path("readingCompleted").asBoolean()).isTrue();
        assertThat(payload.path("checkpointPassed").asBoolean()).isTrue();
        assertThat(payload.path("quizPassed").asBoolean()).isTrue();
        assertThat(payload.path("completedAt").asText()).isEqualTo(completedAt.toString());

        UserRoadmapNodeEntity after = mappedNode(roadmapId);
        assertThat(nodeStateSnapshot(after)).isEqualTo(stateBefore);
        assertThat(after.getCheckInStatus()).isEqualTo(CheckInStatus.MISSING);
        assertThat(after.getQuizStatus()).isEqualTo(QuizStatus.NOT_GENERATED);
        assertThat(after.getArtifactStatus()).isIn(ArtifactStatus.MISSING, ArtifactStatus.NOT_REQUIRED);
        assertThat(after.getCompletionStatus()).isEqualTo(CompletionStatus.INCOMPLETE);
    }

    @Test
    void unmappedLessonCreatesNoEvidenceAndKeepsLegacyCourseHistoryReadable() {
        String ownerId = createOwner("unmapped");
        String roadmapId = enroll(ownerId);
        mappingRepository.deleteAll();
        Instant activityAt = Instant.parse("2026-08-08T01:02:03Z");
        LessonProgressEntity progress = new LessonProgressEntity(
                UUID.randomUUID().toString(), ownerId, MAPPED_LESSON, activityAt);
        progress.updateLearningActivity(true, false, "foundation-summary", activityAt);
        progressRepository.saveAndFlush(progress);
        LessonResponse before = courseLearningService.getLesson(ownerId, MAPPED_LESSON);

        service.migrateOwner(roadmapId, 7);

        assertThat(evidenceRepository.findAllByOwnerId(ownerId)).isEmpty();
        LessonResponse after = courseLearningService.getLesson(ownerId, MAPPED_LESSON);
        assertThat(after).isEqualTo(before);
        assertThat(progressRepository.findByOwnerIdAndLessonId(ownerId, MAPPED_LESSON))
                .get().satisfies(saved -> {
                    assertThat(saved.isVideoCompleted()).isTrue();
                    assertThat(saved.isReadingCompleted()).isFalse();
                    assertThat(saved.getUpdatedAt()).isEqualTo(activityAt);
                });
    }

    @Test
    void migratesOnlyProgressOwnedByTheEnrollmentOwner() {
        String ownerId = createOwner("right-owner");
        String otherOwnerId = createOwner("other-owner");
        String roadmapId = enroll(ownerId);
        completeLesson(ownerId, MAPPED_LESSON, Instant.parse("2026-08-09T01:00:00Z"));
        completeLesson(otherOwnerId, MAPPED_LESSON, Instant.parse("2026-08-09T02:00:00Z"));

        service.migrateOwner(roadmapId, 1);

        assertThat(evidenceRepository.findAllByOwnerId(ownerId)).hasSize(1);
        assertThat(evidenceRepository.findAllByOwnerId(otherOwnerId)).isEmpty();
    }

    @Test
    void preservesPartialFlagsAndNullCompletionForEachMigrationVersion() {
        String ownerId = createOwner("partial");
        String roadmapId = enroll(ownerId);
        Instant activityAt = Instant.parse("2026-08-08T06:07:08Z");
        LessonProgressEntity progress = new LessonProgressEntity(
                UUID.randomUUID().toString(), ownerId, MAPPED_LESSON, activityAt);
        progress.updateLearningActivity(true, false, "controller", activityAt);
        progressRepository.saveAndFlush(progress);

        service.migrateOwner(roadmapId, 1);
        service.migrateOwner(roadmapId, 2);

        assertThat(evidenceRepository.findAllByOwnerIdAndLessonId(ownerId, MAPPED_LESSON))
                .hasSize(2).extracting(LegacyLearningEvidenceEntity::getMigrationVersion)
                .containsExactlyInAnyOrder(1, 2);
        LegacyLearningEvidenceEntity first = evidenceRepository
                .findByOwnerIdAndLessonIdAndMigrationVersion(ownerId, MAPPED_LESSON, 1)
                .orElseThrow();
        JsonNode payload = objectMapper.readTree(first.getEvidenceJson());
        assertThat(first.getOriginalStatus()).isEqualTo("IN_PROGRESS");
        assertThat(payload.path("videoCompleted").asBoolean()).isTrue();
        assertThat(payload.path("readingCompleted").asBoolean()).isFalse();
        assertThat(payload.path("checkpointPassed").asBoolean()).isFalse();
        assertThat(payload.path("quizPassed").asBoolean()).isFalse();
        assertThat(payload.has("completedAt")).isTrue();
        assertThat(payload.path("completedAt").isNull()).isTrue();
    }

    @Test
    void validatesEnrollmentAndMigrationVersionAndFailsWhenMappedStateIsMissing() {
        assertThatThrownBy(() -> service.migrateOwner("missing", 1))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("学习路线绑定不存在");

        String ownerId = createOwner("invalid");
        String roadmapId = enroll(ownerId);
        completeLesson(ownerId, MAPPED_LESSON, Instant.parse("2026-08-09T01:00:00Z"));
        assertThatThrownBy(() -> service.migrateOwner(roadmapId, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("migrationVersion 必须为正整数");

        userNodeRepository.delete(mappedNode(roadmapId));
        assertThatThrownBy(() -> service.migrateOwner(roadmapId, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少映射节点状态");
        assertThat(evidenceRepository.findAll()).isEmpty();
    }

    @Test
    void concurrentRepeatedMigrationCreatesOneEvidenceRow() throws Exception {
        int workers = 4;
        String ownerId = createOwner("race");
        String roadmapId = enroll(ownerId);
        completeLesson(ownerId, MAPPED_LESSON, Instant.parse("2026-08-09T05:00:00Z"));
        CyclicBarrier collision = new CyclicBarrier(workers);
        LegacyCourseMigrationService colliding = new LegacyCourseMigrationService(
                userRoadmapRepository, mappingRepository, progressRepository, userNodeRepository,
                evidenceRepository, objectMapper, transactionManager, () -> await(collision));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < workers; index++) {
                futures.add(executor.submit(() -> {
                    await(start);
                    colliding.migrateOwner(roadmapId, 3);
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                getWithinDeadline(future);
            }
            assertThat(evidenceRepository.findAllByOwnerIdAndLessonId(ownerId, MAPPED_LESSON))
                    .singleElement().satisfies(saved -> assertThat(saved.getMigrationVersion()).isEqualTo(3));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void unrelatedIntegrityViolationIsNotTreatedAsAnIdempotentRace() {
        String ownerId = createOwner("fail-closed");
        String roadmapId = enroll(ownerId);
        completeLesson(ownerId, MAPPED_LESSON, Instant.parse("2026-08-09T05:00:00Z"));
        LegacyLearningEvidenceJpaRepository failingRepository = mock(
                LegacyLearningEvidenceJpaRepository.class, delegatesTo(evidenceRepository));
        DataIntegrityViolationException unrelated =
                new DataIntegrityViolationException("violates unrelated_constraint");
        doThrow(unrelated).when(failingRepository).saveAllAndFlush(any());
        LegacyCourseMigrationService failing = new LegacyCourseMigrationService(
                userRoadmapRepository, mappingRepository, progressRepository, userNodeRepository,
                failingRepository, objectMapper, transactionManager, () -> { });

        assertThatThrownBy(() -> failing.migrateOwner(roadmapId, 1)).isSameAs(unrelated);
    }

    private String createOwner(String prefix) {
        String id = UUID.randomUUID().toString();
        userRepository.saveAndFlush(new UserAccountEntity(
                id, prefix + "-" + id + "@example.com", "hash", "迁移用户", Instant.now()));
        return id;
    }

    private String enroll(String ownerId) {
        return enrollmentService.enroll(ownerId, "studypilot-java-ai", 1).id();
    }

    private UserRoadmapNodeEntity mappedNode(String roadmapId) {
        return userNodeRepository.findByUserRoadmapIdAndNodeId(roadmapId, MAPPED_NODE_ID).orElseThrow();
    }

    private static List<Object> nodeStateSnapshot(UserRoadmapNodeEntity node) {
        List<Object> snapshot = new ArrayList<>();
        snapshot.add(node.getAvailabilityStatus());
        snapshot.add(node.getLearningStatus());
        snapshot.add(node.getCheckInStatus());
        snapshot.add(node.getQuizStatus());
        snapshot.add(node.getArtifactStatus());
        snapshot.add(node.getCompletionStatus());
        snapshot.add(node.getCompletedAt());
        snapshot.add(node.getRowVersion());
        return snapshot;
    }

    private void completeLesson(String ownerId, String lessonId, Instant completedAt) {
        LessonProgressEntity progress = new LessonProgressEntity(
                UUID.randomUUID().toString(), ownerId, lessonId, completedAt.minusSeconds(30));
        progress.updateLearningActivity(true, true, "summary", completedAt.minusSeconds(20));
        progress.markCheckpointPassed(completedAt.minusSeconds(10));
        progress.markQuizPassed(completedAt);
        progressRepository.saveAndFlush(progress);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("线程未在 10 秒内开始");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("并发迁移线程未在 10 秒内抵达碰撞点", exception);
        }
    }

    private static void getWithinDeadline(Future<?> future) throws Exception {
        try {
            future.get(20, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            throw new AssertionError("并发迁移未在 20 秒内完成", exception);
        }
    }
}
