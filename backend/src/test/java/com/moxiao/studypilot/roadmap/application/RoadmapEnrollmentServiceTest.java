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
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodePrerequisiteJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapStageJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapTemplateEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapTemplateJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapEntity;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapNodeJpaRepository;
import com.moxiao.studypilot.shared.error.ConflictException;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
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
    void recognizesOnlyTheSameBindingUniqueConstraintAsRecoverable() {
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

        assertThat(RoadmapEnrollmentService.isSameBindingUniqueConflict(sameBinding)).isTrue();
        assertThat(RoadmapEnrollmentService.isSameBindingUniqueConflict(mysqlSameBinding)).isTrue();
        assertThat(RoadmapEnrollmentService.isSameBindingUniqueConflict(primaryKey)).isFalse();
        assertThat(RoadmapEnrollmentService.isSameBindingUniqueConflict(ownerForeignKey)).isFalse();
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
