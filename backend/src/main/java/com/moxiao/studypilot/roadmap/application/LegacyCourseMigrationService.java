package com.moxiao.studypilot.roadmap.application;

import com.moxiao.studypilot.course.infrastructure.LessonProgressEntity;
import com.moxiao.studypilot.course.infrastructure.LessonProgressJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.LegacyLearningEvidenceEntity;
import com.moxiao.studypilot.roadmap.infrastructure.LegacyLearningEvidenceJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.LegacyLessonRoadmapMappingEntity;
import com.moxiao.studypilot.roadmap.infrastructure.LegacyLessonRoadmapMappingJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapEntity;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapNodeEntity;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapNodeJpaRepository;
import com.moxiao.studypilot.shared.error.ConflictException;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class LegacyCourseMigrationService {

    private static final String UNIQUE_CONSTRAINT = "uk_legacy_evidence_migration";
    private static final int MAX_DUPLICATE_RETRIES = 3;
    private static final Pattern UNIQUE_CONSTRAINT_IN_MESSAGE = Pattern.compile(
            "(?i)(?<![a-z0-9_])(?:[a-z0-9_]+\\.)?" + UNIQUE_CONSTRAINT + "(?![a-z0-9_])");
    private static final Pattern EVIDENCE_TABLE_IN_MESSAGE = Pattern.compile(
            "(?i)(?<![a-z0-9_])legacy_learning_evidence(?![a-z0-9_])");
    private static final Pattern EVIDENCE_PRIMARY_IN_MESSAGE = Pattern.compile(
            "(?i)(?:primary_key_[a-z0-9_]+[^\\r\\n]*legacy_learning_evidence\\s*\\(\\s*id\\s*\\)"
                    + "|legacy_learning_evidence\\.primary(?![a-z0-9_])"
                    + "|for key\\s+['\"`]?(?:[a-z0-9_]+\\.)?primary['\"`]?(?![a-z0-9_]))");

    private final UserRoadmapJpaRepository userRoadmapRepository;
    private final LegacyLessonRoadmapMappingJpaRepository mappingRepository;
    private final LessonProgressJpaRepository progressRepository;
    private final UserRoadmapNodeJpaRepository userNodeRepository;
    private final LegacyLearningEvidenceJpaRepository evidenceRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final Runnable beforeInsert;

    @Autowired
    public LegacyCourseMigrationService(
            UserRoadmapJpaRepository userRoadmapRepository,
            LegacyLessonRoadmapMappingJpaRepository mappingRepository,
            LessonProgressJpaRepository progressRepository,
            UserRoadmapNodeJpaRepository userNodeRepository,
            LegacyLearningEvidenceJpaRepository evidenceRepository,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this(userRoadmapRepository, mappingRepository, progressRepository, userNodeRepository,
                evidenceRepository, objectMapper, transactionManager, () -> { });
    }

    LegacyCourseMigrationService(
            UserRoadmapJpaRepository userRoadmapRepository,
            LegacyLessonRoadmapMappingJpaRepository mappingRepository,
            LessonProgressJpaRepository progressRepository,
            UserRoadmapNodeJpaRepository userNodeRepository,
            LegacyLearningEvidenceJpaRepository evidenceRepository,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            Runnable beforeInsert
    ) {
        this.userRoadmapRepository = userRoadmapRepository;
        this.mappingRepository = mappingRepository;
        this.progressRepository = progressRepository;
        this.userNodeRepository = userNodeRepository;
        this.evidenceRepository = evidenceRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.beforeInsert = beforeInsert;
    }

    public void migrateOwner(String userRoadmapId, int migrationVersion) {
        if (migrationVersion <= 0) {
            throw new IllegalArgumentException("migrationVersion 必须为正整数");
        }
        try {
            transactionTemplate.executeWithoutResult(
                    ignored -> migrateInTransaction(userRoadmapId, migrationVersion, true, null));
        } catch (ConcurrentEvidenceInsertException collision) {
            retryMissingEvidence(userRoadmapId, migrationVersion, collision);
        }
    }

    private void migrateInTransaction(
            String userRoadmapId,
            int migrationVersion,
            boolean invokeBeforeInsert,
            List<EvidenceExpectation> requiredExpectations
    ) {
        MigrationBatch batch = loadBatch(userRoadmapId);
        List<EvidenceExpectation> expectations = expectations(batch, migrationVersion);
        if (requiredExpectations != null && !sameExpectations(requiredExpectations, expectations)) {
            throw new ConflictException("旧课程学习证据迁移期间来源数据发生变化");
        }
        if (expectations.isEmpty()) {
            return;
        }
        Map<String, LegacyLearningEvidenceEntity> existingByLessonId = new HashMap<>();
        evidenceRepository
                .findAllByOwnerIdAndMigrationVersionAndLessonIdIn(
                        batch.enrollment().getOwnerId(), migrationVersion,
                        batch.progressByLessonId().keySet())
                .forEach(evidence -> existingByLessonId.put(evidence.getLessonId(), evidence));
        for (EvidenceExpectation expectation : expectations) {
            LegacyLearningEvidenceEntity existing = existingByLessonId.get(expectation.lessonId());
            if (existing != null && !expectation.matches(existing)) {
                throw new ConflictException("旧课程学习证据与当前路线映射冲突");
            }
        }
        List<LegacyLearningEvidenceEntity> additions = expectations.stream()
                .filter(expectation -> !existingByLessonId.containsKey(expectation.lessonId()))
                .map(EvidenceExpectation::newEntity)
                .toList();
        if (additions.isEmpty()) {
            return;
        }

        if (invokeBeforeInsert) {
            beforeInsert.run();
        }
        try {
            evidenceRepository.saveAllAndFlush(additions);
        } catch (DataIntegrityViolationException exception) {
            if (!isExactDuplicateViolation(exception)) {
                throw exception;
            }
            throw new ConcurrentEvidenceInsertException(exception, expectations);
        }
    }

    private MigrationBatch loadBatch(String userRoadmapId) {
        UserRoadmapEntity enrollment = userRoadmapRepository.findById(userRoadmapId)
                .orElseThrow(() -> new ResourceNotFoundException("学习路线绑定不存在"));
        List<LegacyLessonRoadmapMappingEntity> mappings =
                mappingRepository.findAllByTemplateId(enrollment.getTemplateId());
        if (mappings.isEmpty()) {
            return new MigrationBatch(enrollment, Map.of(), Map.of(), Map.of());
        }

        Map<String, LegacyLessonRoadmapMappingEntity> mappingByLessonId = new HashMap<>();
        for (LegacyLessonRoadmapMappingEntity mapping : mappings) {
            mappingByLessonId.put(mapping.getLessonId(), mapping);
        }
        List<LessonProgressEntity> progress = progressRepository.findAllByOwnerIdAndLessonIdIn(
                enrollment.getOwnerId(), mappingByLessonId.keySet());
        if (progress.isEmpty()) {
            return new MigrationBatch(enrollment, mappingByLessonId, Map.of(), Map.of());
        }

        Set<String> nodeIds = progress.stream()
                .map(item -> mappingByLessonId.get(item.getLessonId()).getNodeId())
                .collect(java.util.stream.Collectors.toSet());
        Map<String, UserRoadmapNodeEntity> stateByNodeId = new HashMap<>();
        userNodeRepository.findAllByUserRoadmapIdAndNodeIdIn(userRoadmapId, nodeIds)
                .forEach(state -> stateByNodeId.put(state.getNodeId(), state));
        for (String nodeId : nodeIds) {
            if (!stateByNodeId.containsKey(nodeId)) {
                throw new IllegalStateException(
                        "学习路线绑定 " + userRoadmapId + " 缺少映射节点状态: " + nodeId);
            }
        }
        Map<String, LessonProgressEntity> progressByLessonId = new HashMap<>();
        progress.forEach(item -> progressByLessonId.put(item.getLessonId(), item));
        return new MigrationBatch(enrollment, mappingByLessonId, progressByLessonId, stateByNodeId);
    }

    private List<EvidenceExpectation> expectations(
            MigrationBatch batch,
            int migrationVersion
    ) {
        return batch.progressByLessonId().values().stream()
                .map(progress -> {
                    String nodeId = batch.mappingByLessonId().get(progress.getLessonId()).getNodeId();
                    UserRoadmapNodeEntity state = batch.stateByNodeId().get(nodeId);
                    return new EvidenceExpectation(
                            stableEvidenceId(batch.enrollment().getOwnerId(), progress.getLessonId(),
                                    migrationVersion),
                            batch.enrollment().getOwnerId(),
                            state.getId(),
                            progress.getLessonId(),
                            progress.getStatus().name(),
                            evidenceJson(progress),
                            migrationVersion);
                })
                .sorted(java.util.Comparator.comparing(EvidenceExpectation::lessonId))
                .toList();
    }

    private String evidenceJson(LessonProgressEntity progress) {
        try {
            ObjectNode evidence = objectMapper.createObjectNode();
            evidence.put("type", "LEGACY_LESSON_PROGRESS");
            evidence.put("videoCompleted", progress.isVideoCompleted());
            evidence.put("readingCompleted", progress.isReadingCompleted());
            evidence.put("checkpointPassed", progress.isCheckpointPassed());
            evidence.put("quizPassed", progress.isQuizPassed());
            if (progress.getCompletedAt() == null) {
                evidence.putNull("completedAt");
            } else {
                evidence.put("completedAt", progress.getCompletedAt().toString());
            }
            return objectMapper.writeValueAsString(evidence);
        } catch (Exception exception) {
            throw new IllegalStateException("无法序列化旧课程学习证据", exception);
        }
    }

    private void retryMissingEvidence(
            String userRoadmapId,
            int migrationVersion,
            ConcurrentEvidenceInsertException initialCollision
    ) {
        ConcurrentEvidenceInsertException latestCollision = initialCollision;
        for (int attempt = 1; attempt <= MAX_DUPLICATE_RETRIES; attempt++) {
            try {
                ConcurrentEvidenceInsertException expectedCollision = latestCollision;
                transactionTemplate.executeWithoutResult(ignored -> migrateInTransaction(
                        userRoadmapId,
                        migrationVersion,
                        false,
                        expectedCollision.expectations()));
                return;
            } catch (ConcurrentEvidenceInsertException collision) {
                latestCollision = collision;
            }
        }
        throw latestCollision.integrityViolation();
    }

    private static boolean sameExpectations(
            List<EvidenceExpectation> first,
            List<EvidenceExpectation> second
    ) {
        return first.equals(second);
    }

    /**
     * Recognizes the two portable duplicate shapes this table can produce: the explicitly named
     * three-column key and the deterministic evidence primary key. MySQL 1062 shapes are covered
     * without requiring Docker; a real-MySQL concurrency run remains an environment-level check.
     */
    static boolean isExactDuplicateViolation(DataIntegrityViolationException exception) {
        boolean uniqueViolation = false;
        boolean exactNamedConstraint = false;
        StringBuilder messages = new StringBuilder();
        Set<Throwable> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        Throwable current = exception;
        while (current != null && visited.add(current)) {
            if (current instanceof ConstraintViolationException constraintViolation) {
                uniqueViolation |= constraintViolation.getKind()
                        == ConstraintViolationException.ConstraintKind.UNIQUE;
                exactNamedConstraint |= isExpectedConstraintName(constraintViolation.getConstraintName());
            }
            if (current instanceof SQLException sqlException) {
                uniqueViolation |= "23505".equals(sqlException.getSQLState())
                        || sqlException.getErrorCode() == 1062;
            }
            String message = current.getMessage();
            if (message != null) {
                messages.append(message).append('\n');
                exactNamedConstraint |= UNIQUE_CONSTRAINT_IN_MESSAGE.matcher(message).find();
            }
            current = current.getCause();
        }
        String combined = messages.toString();
        boolean exactPrimary = EVIDENCE_TABLE_IN_MESSAGE.matcher(combined).find()
                && EVIDENCE_PRIMARY_IN_MESSAGE.matcher(combined).find();
        return uniqueViolation && (exactNamedConstraint || exactPrimary);
    }

    private static boolean isExpectedConstraintName(String constraintName) {
        if (constraintName == null) {
            return false;
        }
        String normalized = constraintName.replace("`", "").replace("\"", "")
                .toLowerCase(java.util.Locale.ROOT);
        return normalized.equals(UNIQUE_CONSTRAINT)
                || normalized.endsWith("." + UNIQUE_CONSTRAINT);
    }

    private static String stableEvidenceId(String ownerId, String lessonId, int migrationVersion) {
        return UUID.nameUUIDFromBytes((ownerId + ":" + lessonId + ":" + migrationVersion)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private record MigrationBatch(
            UserRoadmapEntity enrollment,
            Map<String, LegacyLessonRoadmapMappingEntity> mappingByLessonId,
            Map<String, LessonProgressEntity> progressByLessonId,
            Map<String, UserRoadmapNodeEntity> stateByNodeId
    ) {
    }

    private record EvidenceExpectation(
            String id,
            String ownerId,
            String userRoadmapNodeId,
            String lessonId,
            String originalStatus,
            String evidenceJson,
            int migrationVersion
    ) {
        private boolean matches(LegacyLearningEvidenceEntity existing) {
            return id.equals(existing.getId())
                    && ownerId.equals(existing.getOwnerId())
                    && userRoadmapNodeId.equals(existing.getUserRoadmapNodeId())
                    && lessonId.equals(existing.getLessonId())
                    && originalStatus.equals(existing.getOriginalStatus())
                    && evidenceJson.equals(existing.getEvidenceJson())
                    && migrationVersion == existing.getMigrationVersion();
        }

        private LegacyLearningEvidenceEntity newEntity() {
            return new LegacyLearningEvidenceEntity(
                    id, ownerId, userRoadmapNodeId, lessonId, originalStatus,
                    evidenceJson, migrationVersion, Instant.now());
        }
    }

    private static final class ConcurrentEvidenceInsertException extends RuntimeException {
        private final DataIntegrityViolationException integrityViolation;
        private final List<EvidenceExpectation> expectations;

        private ConcurrentEvidenceInsertException(
                DataIntegrityViolationException integrityViolation,
                List<EvidenceExpectation> expectations
        ) {
            super(integrityViolation);
            this.integrityViolation = integrityViolation;
            this.expectations = List.copyOf(expectations);
        }

        private DataIntegrityViolationException integrityViolation() {
            return integrityViolation;
        }

        private List<EvidenceExpectation> expectations() {
            return expectations;
        }
    }
}
