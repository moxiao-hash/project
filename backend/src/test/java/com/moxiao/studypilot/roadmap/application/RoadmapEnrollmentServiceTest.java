package com.moxiao.studypilot.roadmap.application;

import com.moxiao.studypilot.auth.infrastructure.UserAccountEntity;
import com.moxiao.studypilot.auth.infrastructure.UserAccountJpaRepository;
import com.moxiao.studypilot.auth.infrastructure.UserSessionJpaRepository;
import com.moxiao.studypilot.roadmap.api.CreateRoadmapEnrollmentRequest;
import com.moxiao.studypilot.roadmap.api.RoadmapEnrollmentResponse;
import com.moxiao.studypilot.roadmap.domain.ArtifactStatus;
import com.moxiao.studypilot.roadmap.domain.AvailabilityStatus;
import com.moxiao.studypilot.roadmap.domain.CheckInStatus;
import com.moxiao.studypilot.roadmap.domain.CompletionStatus;
import com.moxiao.studypilot.roadmap.domain.LearningStatus;
import com.moxiao.studypilot.roadmap.domain.QuizStatus;
import com.moxiao.studypilot.roadmap.domain.RoadmapPublicationStatus;
import com.moxiao.studypilot.roadmap.domain.UserRoadmapStatus;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodePrerequisiteEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodePrerequisiteJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapStageEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapStageJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapTemplateEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapTemplateJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapEntity;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapNodeEntity;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapNodeJpaRepository;
import com.moxiao.studypilot.shared.error.ConflictException;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "studypilot.roadmap.catalog-import-enabled=false")
@AutoConfigureMockMvc
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RoadmapEnrollmentServiceTest {

    @Autowired RoadmapEnrollmentService enrollmentService;
    @Autowired RoadmapCatalogImporter importer;
    @Autowired RoadmapTemplateJpaRepository templateRepository;
    @Autowired RoadmapStageJpaRepository stageRepository;
    @Autowired RoadmapNodeJpaRepository nodeRepository;
    @Autowired RoadmapNodePrerequisiteJpaRepository prerequisiteRepository;
    @Autowired UserRoadmapJpaRepository userRoadmapRepository;
    @Autowired UserRoadmapNodeJpaRepository userNodeRepository;
    @Autowired UserAccountJpaRepository userRepository;
    @Autowired UserSessionJpaRepository sessionRepository;
    @Autowired ObjectMapper objectMapper;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @PersistenceContext EntityManager entityManager;

    @BeforeEach
    void resetDatabaseAndImportCatalog() {
        jdbcTemplate.execute("""
                ALTER TABLE user_roadmaps ADD CONSTRAINT IF NOT EXISTS uk_user_roadmap_template
                UNIQUE (owner_id, template_id)
                """);
        jdbcTemplate.execute("""
                ALTER TABLE user_roadmaps ADD CONSTRAINT IF NOT EXISTS uk_user_roadmap_active_slot
                UNIQUE (owner_id, active_slot)
                """);
        userNodeRepository.deleteAll();
        userRoadmapRepository.deleteAll();
        sessionRepository.deleteAll();
        userRepository.deleteAll();
        prerequisiteRepository.deleteAll();
        nodeRepository.deleteAll();
        stageRepository.deleteAll();
        templateRepository.deleteAll();
        importer.importCatalog();
    }

    @Test
    void enrollsPublishedRoadmapAndInitializesEveryNodeState() throws Exception {
        String ownerId = createUser("enroll");

        RoadmapEnrollmentResponse response = enrollmentService.enroll(ownerId, "studypilot-java-ai", 1);

        UserRoadmapEntity roadmap = userRoadmapRepository.findById(response.id()).orElseThrow();
        assertThat(roadmap.getOwnerId()).isEqualTo(ownerId);
        assertThat(roadmap.getStatus()).isEqualTo(UserRoadmapStatus.ACTIVE);
        assertThat(roadmap.getActiveSlot()).isEqualTo("CURRENT");
        assertThat(response.roadmapCode()).isEqualTo("studypilot-java-ai");
        assertThat(response.templateVersion()).isEqualTo(1);
        assertThat(response.title()).isEqualTo("StudyPilot Java + AI 学习路线");

        var states = userNodeRepository.findAllByUserRoadmapId(roadmap.getId());
        assertThat(states).hasSize(64);
        Set<String> nodesWithPrerequisites = prerequisiteRepository.findAllByTemplateId(roadmap.getTemplateId())
                .stream().map(edge -> edge.getNodeId()).collect(Collectors.toSet());
        assertThat(states).allSatisfy(state -> {
            assertThat(state.getAvailabilityStatus()).isEqualTo(
                    nodesWithPrerequisites.contains(state.getNodeId())
                            ? AvailabilityStatus.LOCKED : AvailabilityStatus.AVAILABLE);
            assertThat(state.getLearningStatus()).isEqualTo(LearningStatus.NOT_STARTED);
            assertThat(state.getCheckInStatus()).isEqualTo(CheckInStatus.MISSING);
            assertThat(state.getQuizStatus()).isEqualTo(QuizStatus.NOT_GENERATED);
            assertThat(state.getCompletionStatus()).isEqualTo(CompletionStatus.INCOMPLETE);
        });
        assertThat(states).anySatisfy(state -> assertThat(state.getAvailabilityStatus())
                .isEqualTo(AvailabilityStatus.AVAILABLE));

        var templatesById = nodeRepository.findAllByTemplateIdOrderByStageIdAscNodeOrderAsc(roadmap.getTemplateId())
                .stream().collect(Collectors.toMap(node -> node.getId(), node -> node));
        for (var state : states) {
            boolean required = objectMapper.readTree(
                    templatesById.get(state.getNodeId()).getArtifactRequirementJson()).get("required").asBoolean();
            assertThat(state.getArtifactStatus()).isEqualTo(
                    required ? ArtifactStatus.MISSING : ArtifactStatus.NOT_REQUIRED);
        }
    }

    @Test
    void repeatedEnrollmentReturnsSameResourceWithoutDuplicateState() {
        String ownerId = createUser("repeat");

        RoadmapEnrollmentResponse first = enrollmentService.enroll(ownerId, "studypilot-java-ai", 1);
        RoadmapEnrollmentResponse second = enrollmentService.enroll(ownerId, "studypilot-java-ai", 1);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(userRoadmapRepository.findAll()).hasSize(1);
        assertThat(userNodeRepository.findAll()).hasSize(64);
    }

    @Test
    void invalidArtifactRequirementRollsBackTheWholeEnrollment() {
        String ownerId = createUser("bad-artifact");
        String nodeId = nodeRepository.findAll().get(0).getId();
        jdbcTemplate.update(
                "UPDATE roadmap_nodes SET artifact_requirement_json = ? WHERE id = ?",
                "{not-json", nodeId);

        assertThatThrownBy(() -> enrollmentService.enroll(ownerId, "studypilot-java-ai", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("路线节点产物要求配置无效: " + nodeId);
        assertThat(userRoadmapRepository.findAll()).isEmpty();
        assertThat(userNodeRepository.findAll()).isEmpty();
    }

    @Test
    void generatedNodeStateIdsUseStableNameUuid() {
        String ownerId = createUser("stable-id");

        RoadmapEnrollmentResponse response = enrollmentService.enroll(ownerId, "studypilot-java-ai", 1);

        assertThat(userNodeRepository.findAllByUserRoadmapId(response.id())).allSatisfy(state ->
                assertThat(state.getId()).isEqualTo(UUID.nameUUIDFromBytes(
                        (response.id() + ":" + state.getNodeId()).getBytes(StandardCharsets.UTF_8)).toString()));
    }

    @Test
    void missingOrUnpublishedTemplateLooksNotFound() {
        String ownerId = createUser("not-found");
        templateRepository.save(new RoadmapTemplateEntity(
                "draft-v1", "draft", 1, "草稿", "草稿", RoadmapPublicationStatus.DRAFT,
                "d".repeat(64), Instant.now()));

        assertThatThrownBy(() -> enrollmentService.enroll(ownerId, "missing", 1))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("路线版本不存在");
        assertThatThrownBy(() -> enrollmentService.enroll(ownerId, "draft", 1))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("路线版本不存在");
        assertThat(userRoadmapRepository.findAll()).isEmpty();
    }

    @Test
    void refusesAnotherTemplateWhileCurrentEnrollmentRemainsActive() {
        String ownerId = createUser("conflict");
        RoadmapTemplateEntity other = templateRepository.save(new RoadmapTemplateEntity(
                "other-v1", "other", 1, "另一条路线", "描述", RoadmapPublicationStatus.PUBLISHED,
                "e".repeat(64), Instant.now()));
        UserRoadmapEntity current = userRoadmapRepository.saveAndFlush(
                new UserRoadmapEntity(UUID.randomUUID().toString(), ownerId, other.getId(), Instant.now()));

        assertThatThrownBy(() -> enrollmentService.enroll(ownerId, "studypilot-java-ai", 1))
                .isInstanceOf(ConflictException.class).hasMessage("已有生效中的学习路线");

        assertThat(userRoadmapRepository.findAll()).singleElement().satisfies(saved -> {
            assertThat(saved.getId()).isEqualTo(current.getId());
            assertThat(saved.getStatus()).isEqualTo(UserRoadmapStatus.ACTIVE);
            assertThat(saved.getActiveSlot()).isEqualTo("CURRENT");
        });
        assertThat(userNodeRepository.findAll()).isEmpty();
    }

    @Test
    void concurrentSameEnrollmentRecoversOnlyTheWinningResource() throws Exception {
        int workers = 4;
        String ownerId = createUser("concurrent");
        CountDownLatch start = new CountDownLatch(1);
        CyclicBarrier collision = new CyclicBarrier(workers);
        RoadmapEnrollmentService collidingService = new RoadmapEnrollmentService(
                templateRepository, userRoadmapRepository, nodeRepository, prerequisiteRepository,
                userNodeRepository, objectMapper, transactionManager, () -> await(collision));
        ExecutorService executor = Executors.newFixedThreadPool(workers);

        try {
            List<Future<RoadmapEnrollmentResponse>> futures = new ArrayList<>();
            for (int index = 0; index < workers; index++) {
                futures.add(executor.submit(() -> {
                    await(start);
                    return collidingService.enroll(ownerId, "studypilot-java-ai", 1);
                }));
            }
            start.countDown();

            Set<String> ids = new java.util.HashSet<>();
            for (Future<RoadmapEnrollmentResponse> future : futures) {
                ids.add(getWithinDeadline(future).id());
            }
            assertThat(ids).hasSize(1);
            assertThat(userRoadmapRepository.findAll()).hasSize(1);
            assertThat(userNodeRepository.findAll()).hasSize(64);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentDifferentTemplatesLeaveOneWinnerAndOneConflict() throws Exception {
        int workers = 2;
        String ownerId = createUser("different-template-race");
        RoadmapTemplateEntity alternate = cloneCatalogAsAlternateTemplate();
        CountDownLatch start = new CountDownLatch(1);
        CyclicBarrier collision = new CyclicBarrier(workers);
        RoadmapEnrollmentService collidingService = new RoadmapEnrollmentService(
                templateRepository, userRoadmapRepository, nodeRepository, prerequisiteRepository,
                userNodeRepository, objectMapper, transactionManager, () -> await(collision));
        ExecutorService executor = Executors.newFixedThreadPool(workers);

        try {
            List<Future<RoadmapEnrollmentResponse>> futures = List.of(
                    executor.submit(() -> {
                        await(start);
                        return collidingService.enroll(ownerId, "studypilot-java-ai", 1);
                    }),
                    executor.submit(() -> {
                        await(start);
                        return collidingService.enroll(
                                ownerId, alternate.getRoadmapCode(), alternate.getTemplateVersion());
                    })
            );
            start.countDown();

            List<RoadmapEnrollmentResponse> successes = new ArrayList<>();
            List<Throwable> failures = new ArrayList<>();
            for (Future<RoadmapEnrollmentResponse> future : futures) {
                try {
                    successes.add(getWithinDeadline(future));
                } catch (ExecutionException exception) {
                    failures.add(exception.getCause());
                }
            }

            assertThat(successes).hasSize(1);
            assertThat(failures).singleElement().satisfies(failure -> {
                assertThat(failure).isInstanceOf(ConflictException.class);
                assertThat(failure.getMessage()).isEqualTo("已有生效中的学习路线");
            });
            assertThat(userRoadmapRepository.findAll()).singleElement().satisfies(winner -> {
                assertThat(winner.getId()).isEqualTo(successes.get(0).id());
                assertThat(winner.getStatus()).isEqualTo(UserRoadmapStatus.ACTIVE);
                assertThat(winner.getActiveSlot()).isEqualTo("CURRENT");
            });
            assertThat(userNodeRepository.findAll()).hasSize(64)
                    .allSatisfy(state -> assertThat(state.getUserRoadmapId())
                            .isEqualTo(successes.get(0).id()));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void recognizesOnlyTheTwoEnrollmentUniqueConstraintsAsRecoverable() {
        SQLException h2Duplicate = new SQLException("duplicate", "23505", 23505);
        var sameBinding = new DataIntegrityViolationException("insert failed",
                new ConstraintViolationException(
                        "constraint failed", h2Duplicate,
                        ConstraintViolationException.ConstraintKind.UNIQUE,
                        "UK_USER_ROADMAP_TEMPLATE"));
        SQLException mysqlDuplicate = new SQLException(
                "Duplicate entry 'owner-template' for key 'uk_user_roadmap_template'", "23000", 1062);
        var mysqlSameBinding = new DataIntegrityViolationException("insert failed", mysqlDuplicate);
        var primaryKey = new DataIntegrityViolationException("insert failed",
                new ConstraintViolationException(
                        "constraint failed", h2Duplicate,
                        ConstraintViolationException.ConstraintKind.UNIQUE,
                        "PRIMARY"));
        SQLException foreignKey = new SQLException(
                "Referential integrity constraint violation: FK_USER_ROADMAPS_OWNER", "23506", 23506);
        var ownerForeignKey = new DataIntegrityViolationException("insert failed", foreignKey);
        var activeSlot = new DataIntegrityViolationException("insert failed",
                new ConstraintViolationException(
                        "constraint failed", h2Duplicate,
                        ConstraintViolationException.ConstraintKind.UNIQUE,
                        "UK_USER_ROADMAP_ACTIVE_SLOT"));
        var similarlyPrefixed = new DataIntegrityViolationException("insert failed",
                new ConstraintViolationException(
                        "constraint failed", h2Duplicate,
                        ConstraintViolationException.ConstraintKind.UNIQUE,
                        "UK_USER_ROADMAP_ACTIVE_SLOT_SHADOW"));

        assertThat(RoadmapEnrollmentService.isEnrollmentUniqueConflict(sameBinding)).isTrue();
        assertThat(RoadmapEnrollmentService.isEnrollmentUniqueConflict(mysqlSameBinding)).isTrue();
        assertThat(RoadmapEnrollmentService.isEnrollmentUniqueConflict(activeSlot)).isTrue();
        assertThat(RoadmapEnrollmentService.isEnrollmentUniqueConflict(similarlyPrefixed)).isFalse();
        assertThat(RoadmapEnrollmentService.isEnrollmentUniqueConflict(primaryKey)).isFalse();
        assertThat(RoadmapEnrollmentService.isEnrollmentUniqueConflict(ownerForeignKey)).isFalse();
    }

    @Test
    void migrationKeepsEnrollmentUniqueConstraintNamesAndColumnsStable() throws IOException {
        String migration;
        try (var input = new ClassPathResource(
                "db/migration/V24__create_roadmap_foundation.sql").getInputStream()) {
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("\\s+", " ");
        }

        assertThat(migration).contains(
                "CONSTRAINT uk_user_roadmap_template UNIQUE (owner_id, template_id)",
                "CONSTRAINT uk_user_roadmap_active_slot UNIQUE (owner_id, active_slot)");
    }

    @Test
    void foreignKeyAndChildIntegrityFailuresAreNotRecovered() {
        String ownerId = createUser("integrity-failure");
        UserRoadmapEntity apparentWinner = new UserRoadmapEntity(
                UUID.randomUUID().toString(), ownerId, "studypilot-java-ai-v1", Instant.now());
        SQLException foreignKeySql = new SQLException("owner FK violation", "23506", 23506);
        DataIntegrityViolationException foreignKeyFailure = new DataIntegrityViolationException(
                "owner FK violation",
                new ConstraintViolationException(
                        "constraint failed", foreignKeySql,
                        ConstraintViolationException.ConstraintKind.FOREIGN_KEY,
                        "FK_USER_ROADMAPS_OWNER"));
        UserRoadmapJpaRepository failingEnrollments = mock(
                UserRoadmapJpaRepository.class, delegatesTo(userRoadmapRepository));
        doReturn(Optional.empty(), Optional.of(apparentWinner)).when(failingEnrollments)
                .findByOwnerIdAndTemplateId(ownerId, "studypilot-java-ai-v1");
        doReturn(Optional.empty()).when(failingEnrollments)
                .findByOwnerIdAndActiveSlot(ownerId, "CURRENT");
        doThrow(foreignKeyFailure).when(failingEnrollments).saveAndFlush(any());
        RoadmapEnrollmentService foreignKeyService = new RoadmapEnrollmentService(
                templateRepository, failingEnrollments, nodeRepository, prerequisiteRepository,
                userNodeRepository, objectMapper, transactionManager, () -> { });

        assertThatThrownBy(() -> foreignKeyService.enroll(ownerId, "studypilot-java-ai", 1))
                .isSameAs(foreignKeyFailure);
        verify(failingEnrollments, times(1))
                .findByOwnerIdAndTemplateId(ownerId, "studypilot-java-ai-v1");
        assertThat(userRoadmapRepository.findAll()).isEmpty();

        UserRoadmapNodeJpaRepository failingUserNodes = mock(
                UserRoadmapNodeJpaRepository.class, delegatesTo(userNodeRepository));
        doThrow(new DataIntegrityViolationException("child node FK violation"))
                .when(failingUserNodes).saveAll(any());
        RoadmapEnrollmentService failingService = new RoadmapEnrollmentService(
                templateRepository, userRoadmapRepository, nodeRepository, prerequisiteRepository,
                failingUserNodes, objectMapper, transactionManager, () -> { });

        assertThatThrownBy(() -> failingService.enroll(ownerId, "studypilot-java-ai", 1))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessage("child node FK violation");
        assertThat(userRoadmapRepository.findAll()).isEmpty();
        assertThat(userNodeRepository.findAll()).isEmpty();
    }

    @Test
    void loadsTemplateNodesAndPrerequisitesOnceWithoutPerNodeQueries() {
        String ownerId = createUser("bulk-load");
        RoadmapNodeJpaRepository countingNodes = mock(
                RoadmapNodeJpaRepository.class, delegatesTo(nodeRepository));
        RoadmapNodePrerequisiteJpaRepository countingPrerequisites = mock(
                RoadmapNodePrerequisiteJpaRepository.class, delegatesTo(prerequisiteRepository));
        RoadmapEnrollmentService countingService = new RoadmapEnrollmentService(
                templateRepository, userRoadmapRepository, countingNodes, countingPrerequisites,
                userNodeRepository, objectMapper, transactionManager, () -> { });

        countingService.enroll(ownerId, "studypilot-java-ai", 1);

        verify(countingNodes, times(1))
                .findAllByTemplateIdOrderByStageIdAscNodeOrderAsc("studypilot-java-ai-v1");
        verify(countingPrerequisites, times(1)).findAllByTemplateId("studypilot-java-ai-v1");
        verify(countingPrerequisites, times(0)).findAllByNodeId(any());
    }

    @Test
    void keepsMultiPrerequisiteNodeLockedUntilEveryPrerequisiteIsCompleted() {
        String ownerId = createUser("multi-prerequisite");
        RoadmapEnrollmentResponse enrollment = enrollmentService.enroll(
                ownerId, "studypilot-java-ai", 1);
        List<RoadmapNodePrerequisiteEntity> edges = multiPrerequisiteEdges();

        completeNode(enrollment.id(), edges.get(0).getPrerequisiteNodeId());
        enrollmentService.recalculateAvailability(enrollment.id());

        assertThat(userNode(enrollment.id(), edges.get(0).getNodeId()).getAvailabilityStatus())
                .isEqualTo(AvailabilityStatus.LOCKED);

        edges.forEach(edge -> completeNode(enrollment.id(), edge.getPrerequisiteNodeId()));
        enrollmentService.recalculateAvailability(enrollment.id());

        assertThat(userNode(enrollment.id(), edges.get(0).getNodeId()).getAvailabilityStatus())
                .isEqualTo(AvailabilityStatus.AVAILABLE);
    }

    @Test
    void neverRelocksCompletedNodeWhenItsPrerequisiteIsIncomplete() {
        String ownerId = createUser("completed-preserved");
        RoadmapEnrollmentResponse enrollment = enrollmentService.enroll(
                ownerId, "studypilot-java-ai", 1);
        RoadmapNodePrerequisiteEntity edge = prerequisiteRepository
                .findAllByTemplateId("studypilot-java-ai-v1").get(0);
        completeNode(enrollment.id(), edge.getNodeId());

        enrollmentService.recalculateAvailability(enrollment.id());

        var completed = userNode(enrollment.id(), edge.getNodeId());
        assertThat(completed.getCompletionStatus()).isEqualTo(CompletionStatus.COMPLETED);
        assertThat(completed.getAvailabilityStatus()).isEqualTo(AvailabilityStatus.AVAILABLE);
    }

    @Test
    void makesRootNodesAvailableDuringDeterministicRecalculation() {
        String ownerId = createUser("root-available");
        RoadmapEnrollmentResponse enrollment = enrollmentService.enroll(
                ownerId, "studypilot-java-ai", 1);
        Set<String> dependentNodeIds = prerequisiteRepository
                .findAllByTemplateId("studypilot-java-ai-v1").stream()
                .map(RoadmapNodePrerequisiteEntity::getNodeId)
                .collect(Collectors.toSet());
        var root = userNodeRepository.findAllByUserRoadmapId(enrollment.id()).stream()
                .filter(state -> !dependentNodeIds.contains(state.getNodeId()))
                .findFirst().orElseThrow();
        lockNode(enrollment.id(), root.getNodeId());

        enrollmentService.recalculateAvailability(enrollment.id());

        assertThat(userNode(enrollment.id(), root.getNodeId()).getAvailabilityStatus())
                .isEqualTo(AvailabilityStatus.AVAILABLE);
    }

    @Test
    void failsClosedWithContextWhenPrerequisiteStateIsMissing() {
        String ownerId = createUser("missing-prerequisite-state");
        RoadmapEnrollmentResponse enrollment = enrollmentService.enroll(
                ownerId, "studypilot-java-ai", 1);
        RoadmapNodePrerequisiteEntity edge = prerequisiteRepository
                .findAllByTemplateId("studypilot-java-ai-v1").get(0);
        UserRoadmapNodeEntity prerequisite = userNode(
                enrollment.id(), edge.getPrerequisiteNodeId());
        userNodeRepository.deleteById(prerequisite.getId());
        userNodeRepository.flush();

        assertThatThrownBy(() -> enrollmentService.recalculateAvailability(enrollment.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(enrollment.id())
                .hasMessageContaining(edge.getNodeId())
                .hasMessageContaining(edge.getPrerequisiteNodeId());
    }

    @Test
    void relocksIncompleteNodeWhenAnyPrerequisiteBecomesIncomplete() {
        String ownerId = createUser("relock");
        RoadmapEnrollmentResponse enrollment = enrollmentService.enroll(
                ownerId, "studypilot-java-ai", 1);
        List<RoadmapNodePrerequisiteEntity> edges = multiPrerequisiteEdges();
        edges.forEach(edge -> completeNode(enrollment.id(), edge.getPrerequisiteNodeId()));
        enrollmentService.recalculateAvailability(enrollment.id());
        assertThat(userNode(enrollment.id(), edges.get(0).getNodeId()).getAvailabilityStatus())
                .isEqualTo(AvailabilityStatus.AVAILABLE);

        makeNodeIncomplete(enrollment.id(), edges.get(0).getPrerequisiteNodeId());
        enrollmentService.recalculateAvailability(enrollment.id());

        assertThat(userNode(enrollment.id(), edges.get(0).getNodeId()).getAvailabilityStatus())
                .isEqualTo(AvailabilityStatus.LOCKED);
    }

    @Test
    void recalculationUsesThreeFixedRepositoryCallsAndDoesNotChurnUnchangedStates() {
        String ownerId = createUser("bounded-recalculation");
        RoadmapEnrollmentResponse enrollment = enrollmentService.enroll(
                ownerId, "studypilot-java-ai", 1);
        UserRoadmapJpaRepository countingEnrollments = mock(
                UserRoadmapJpaRepository.class, delegatesTo(userRoadmapRepository));
        UserRoadmapNodeJpaRepository countingStates = mock(
                UserRoadmapNodeJpaRepository.class, delegatesTo(userNodeRepository));
        RoadmapNodePrerequisiteJpaRepository countingPrerequisites = mock(
                RoadmapNodePrerequisiteJpaRepository.class, delegatesTo(prerequisiteRepository));
        RoadmapEnrollmentService countingService = new RoadmapEnrollmentService(
                templateRepository, countingEnrollments, nodeRepository, countingPrerequisites,
                countingStates, objectMapper, transactionManager, () -> { });
        Map<String, Long> versionsBefore = userNodeRepository
                .findAllByUserRoadmapId(enrollment.id()).stream()
                .collect(Collectors.toMap(UserRoadmapNodeEntity::getId,
                        UserRoadmapNodeEntity::getRowVersion));
        Map<String, Instant> timestampsBefore = userNodeRepository
                .findAllByUserRoadmapId(enrollment.id()).stream()
                .collect(Collectors.toMap(UserRoadmapNodeEntity::getId,
                        UserRoadmapNodeEntity::getUpdatedAt));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            countingService.recalculateAvailability(enrollment.id());
            countingStates.flush();
            entityManager.clear();
        });

        verify(countingEnrollments, times(1)).findByIdForUpdate(enrollment.id());
        verify(countingEnrollments, times(0)).findById(enrollment.id());
        verify(countingStates, times(1)).findAllByUserRoadmapId(enrollment.id());
        verify(countingPrerequisites, times(1))
                .findAllByTemplateId("studypilot-java-ai-v1");
        verify(countingStates, times(0)).findByUserRoadmapIdAndNodeId(any(), any());
        assertThat(userNodeRepository.findAllByUserRoadmapId(enrollment.id()))
                .allSatisfy(state -> {
                    assertThat(state.getRowVersion()).isEqualTo(versionsBefore.get(state.getId()));
                    assertThat(state.getUpdatedAt()).isEqualTo(timestampsBefore.get(state.getId()));
                });
    }

    @Test
    void recalculationParticipatesInOuterCompletionTransactionAndRollsBackAtomically() {
        String ownerId = createUser("transaction-composition");
        RoadmapEnrollmentResponse enrollment = enrollmentService.enroll(
                ownerId, "studypilot-java-ai", 1);
        List<RoadmapNodePrerequisiteEntity> edges = multiPrerequisiteEdges();
        String dependentNodeId = edges.get(0).getNodeId();
        TransactionTemplate outerTransaction = new TransactionTemplate(transactionManager);

        outerTransaction.executeWithoutResult(status -> {
            edges.forEach(edge -> completeNode(
                    enrollment.id(), edge.getPrerequisiteNodeId()));

            enrollmentService.recalculateAvailability(enrollment.id());

            assertThat(userNode(enrollment.id(), dependentNodeId).getAvailabilityStatus())
                    .isEqualTo(AvailabilityStatus.AVAILABLE);
            status.setRollbackOnly();
        });

        assertThat(userNode(enrollment.id(), dependentNodeId).getAvailabilityStatus())
                .isEqualTo(AvailabilityStatus.LOCKED);
        assertThat(edges).allSatisfy(edge ->
                assertThat(userNode(enrollment.id(), edge.getPrerequisiteNodeId())
                        .getCompletionStatus()).isEqualTo(CompletionStatus.INCOMPLETE));
    }

    @Test
    void concurrentPrerequisiteCompletionsCannotLoseTheFinalUnlock() throws Exception {
        String ownerId = createUser("concurrent-prerequisites");
        RoadmapEnrollmentResponse enrollment = enrollmentService.enroll(
                ownerId, "studypilot-java-ai", 1);
        List<RoadmapNodePrerequisiteEntity> edges = multiPrerequisiteEdges();
        String dependentNodeId = edges.get(0).getNodeId();
        edges.stream().skip(2).forEach(edge ->
                completeNode(enrollment.id(), edge.getPrerequisiteNodeId()));
        CyclicBarrier bothPrerequisitesUpdated = new CyclicBarrier(2);
        CountDownLatch bothRecalculationsFinished = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            List<? extends Future<?>> futures = edges.stream().limit(2)
                    .map(edge -> executor.submit(() -> {
                        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                            completeNode(enrollment.id(), edge.getPrerequisiteNodeId());
                            await(bothPrerequisitesUpdated);
                            enrollmentService.recalculateAvailability(enrollment.id());
                            bothRecalculationsFinished.countDown();
                            awaitBriefly(bothRecalculationsFinished);
                        });
                    }))
                    .toList();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(edges).allSatisfy(edge ->
                assertThat(userNode(enrollment.id(), edge.getPrerequisiteNodeId())
                        .getCompletionStatus()).isEqualTo(CompletionStatus.COMPLETED));
        assertThat(userNode(enrollment.id(), dependentNodeId).getAvailabilityStatus())
                .isEqualTo(AvailabilityStatus.AVAILABLE);
    }

    @Test
    void enrollmentEndpointRequiresAuthenticationAndIsIdempotentWithoutOwnerInPayload() throws Exception {
        String body = """
                {"roadmapCode":"studypilot-java-ai","templateVersion":1}
                """;
        mockMvc.perform(post("/api/roadmap-enrollments")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        Registration registration = registerUser();
        MvcResult first = mockMvc.perform(post("/api/roadmap-enrollments")
                        .header("Authorization", "Bearer " + registration.token())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerId").doesNotExist())
                .andExpect(jsonPath("$.roadmapCode").value("studypilot-java-ai"))
                .andReturn();
        MvcResult second = mockMvc.perform(post("/api/roadmap-enrollments")
                        .header("Authorization", "Bearer " + registration.token())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerId").doesNotExist())
                .andReturn();

        JsonNode firstJson = objectMapper.readTree(first.getResponse().getContentAsString());
        JsonNode secondJson = objectMapper.readTree(second.getResponse().getContentAsString());
        assertThat(secondJson.get("id").asText()).isEqualTo(firstJson.get("id").asText());
    }

    @Test
    void rejectsInvalidEnrollmentRequestAndNeverExposesAnOwnerField() throws Exception {
        assertThat(CreateRoadmapEnrollmentRequest.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("roadmapCode", "templateVersion");
        Registration registration = registerUser();

        mockMvc.perform(post("/api/roadmap-enrollments")
                        .header("Authorization", "Bearer " + registration.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roadmapCode\":\"\",\"templateVersion\":0}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/roadmap-enrollments")
                        .header("Authorization", "Bearer " + registration.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "roadmapCode":"studypilot-java-ai",
                                  "templateVersion":1,
                                  "ownerId":"attacker-controlled-owner"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerId").doesNotExist());

        assertThat(userRoadmapRepository.findAll()).singleElement()
                .satisfies(enrollment -> assertThat(enrollment.getOwnerId()).isEqualTo(registration.userId()));
    }

    private String createUser(String suffix) {
        String id = UUID.randomUUID().toString();
        userRepository.saveAndFlush(new UserAccountEntity(
                id, suffix + "-" + id + "@example.com", "hash", suffix, Instant.now()));
        return id;
    }

    private List<RoadmapNodePrerequisiteEntity> multiPrerequisiteEdges() {
        return prerequisiteRepository.findAllByTemplateId("studypilot-java-ai-v1").stream()
                .collect(Collectors.groupingBy(RoadmapNodePrerequisiteEntity::getNodeId))
                .values().stream()
                .filter(edges -> edges.size() > 1)
                .findFirst()
                .orElseThrow(() -> new AssertionError("测试目录必须包含多先修节点"));
    }

    private UserRoadmapNodeEntity userNode(String enrollmentId, String nodeId) {
        return userNodeRepository.findByUserRoadmapIdAndNodeId(enrollmentId, nodeId).orElseThrow();
    }

    private void completeNode(String enrollmentId, String nodeId) {
        jdbcTemplate.update("""
                UPDATE user_roadmap_nodes
                SET completion_status = 'COMPLETED', availability_status = 'AVAILABLE', completed_at = ?
                WHERE user_roadmap_id = ? AND node_id = ?
                """, Instant.now(), enrollmentId, nodeId);
    }

    private void makeNodeIncomplete(String enrollmentId, String nodeId) {
        jdbcTemplate.update("""
                UPDATE user_roadmap_nodes
                SET completion_status = 'INCOMPLETE', completed_at = NULL
                WHERE user_roadmap_id = ? AND node_id = ?
                """, enrollmentId, nodeId);
    }

    private void lockNode(String enrollmentId, String nodeId) {
        jdbcTemplate.update("""
                UPDATE user_roadmap_nodes SET availability_status = 'LOCKED'
                WHERE user_roadmap_id = ? AND node_id = ?
                """, enrollmentId, nodeId);
    }

    private RoadmapTemplateEntity cloneCatalogAsAlternateTemplate() {
        String sourceTemplateId = "studypilot-java-ai-v1";
        String targetTemplateId = "studypilot-java-ai-alt-v1";
        RoadmapTemplateEntity alternate = templateRepository.saveAndFlush(new RoadmapTemplateEntity(
                targetTemplateId,
                "studypilot-java-ai-alt",
                1,
                "StudyPilot Java + AI 备选路线",
                "用于并发绑定验证的完整备选路线",
                RoadmapPublicationStatus.PUBLISHED,
                "a".repeat(64),
                Instant.now()
        ));

        Map<String, String> stageIds = new HashMap<>();
        List<RoadmapStageEntity> stages = stageRepository.findAll().stream()
                .filter(stage -> stage.getTemplateId().equals(sourceTemplateId))
                .map(stage -> {
                    String targetStageId = targetTemplateId + "-" + stage.getStageCode();
                    stageIds.put(stage.getId(), targetStageId);
                    return new RoadmapStageEntity(
                            targetStageId, targetTemplateId, stage.getStageCode(), stage.getStageOrder(),
                            stage.getTitle(), stage.getDescription(), stage.getGraduationProjectTitle());
                })
                .toList();
        stageRepository.saveAllAndFlush(stages);

        Map<String, String> nodeIds = new HashMap<>();
        List<RoadmapNodeEntity> nodes = nodeRepository
                .findAllByTemplateIdOrderByStageIdAscNodeOrderAsc(sourceTemplateId).stream()
                .map(node -> {
                    String targetNodeId = targetTemplateId + "-" + node.getNodeCode();
                    nodeIds.put(node.getId(), targetNodeId);
                    return new RoadmapNodeEntity(
                            targetNodeId, targetTemplateId, stageIds.get(node.getStageId()),
                            node.getNodeCode(), node.getNodeOrder(), node.getTitle(),
                            node.getObjectivesJson(), node.getHighFrequencyJson(),
                            node.getCommonMistakesJson(), node.getSearchKeywordsJson(),
                            node.getArtifactRequirementJson(), node.getQuizBlueprintJson(),
                            node.getEstimatedMinutes(), node.getPracticeMinutes(),
                            node.getDifficulty(), node.isRequiredNode());
                })
                .toList();
        nodeRepository.saveAllAndFlush(nodes);

        List<RoadmapNodePrerequisiteEntity> prerequisites = prerequisiteRepository
                .findAllByTemplateId(sourceTemplateId).stream()
                .map(edge -> {
                    String nodeId = nodeIds.get(edge.getNodeId());
                    String prerequisiteId = nodeIds.get(edge.getPrerequisiteNodeId());
                    String edgeId = UUID.nameUUIDFromBytes(
                            (nodeId + ":" + prerequisiteId).getBytes(StandardCharsets.UTF_8)).toString();
                    return new RoadmapNodePrerequisiteEntity(
                            edgeId, targetTemplateId, nodeId, prerequisiteId);
                })
                .toList();
        prerequisiteRepository.saveAllAndFlush(prerequisites);
        return alternate;
    }

    private Registration registerUser() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "roadmap-%d@example.com",
                                  "password": "Password123!",
                                  "displayName": "路线用户"
                                }
                                """.formatted(System.nanoTime())))
                .andExpect(status().isCreated()).andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Registration(response.get("accessToken").asText(), response.get("user").get("id").asText());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("并发绑定未在 10 秒内开始");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            throw new AssertionError("并发绑定线程未在 10 秒内抵达碰撞点", exception);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void awaitBriefly(CountDownLatch latch) {
        try {
            latch.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static RoadmapEnrollmentResponse getWithinDeadline(Future<RoadmapEnrollmentResponse> future)
            throws ExecutionException, InterruptedException {
        try {
            return future.get(20, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            throw new AssertionError("并发绑定未在 20 秒内完成", exception);
        }
    }

    private record Registration(String token, String userId) {
    }
}
