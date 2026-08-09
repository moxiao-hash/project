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
    private static final Pattern UNIQUE_CONSTRAINT_IN_MESSAGE = Pattern.compile(
            "(?i)(?<![a-z0-9_])(?:[a-z0-9_]+\\.)?" + UNIQUE_CONSTRAINT + "(?![a-z0-9_])");

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
                    ignored -> migrateInTransaction(userRoadmapId, migrationVersion));
        } catch (ConcurrentEvidenceInsertException collision) {
            recoverExactDuplicate(userRoadmapId, migrationVersion, collision);
        }
    }

    private void migrateInTransaction(String userRoadmapId, int migrationVersion) {
        MigrationBatch batch = loadBatch(userRoadmapId);
        if (batch.progressByLessonId().isEmpty()) {
            return;
        }
        Set<String> existingLessonIds = evidenceRepository
                .findAllByOwnerIdAndMigrationVersionAndLessonIdIn(
                        batch.enrollment().getOwnerId(), migrationVersion,
                        batch.progressByLessonId().keySet())
                .stream().map(LegacyLearningEvidenceEntity::getLessonId)
                .collect(java.util.stream.Collectors.toSet());
        List<LegacyLearningEvidenceEntity> additions = batch.progressByLessonId().values().stream()
                .filter(progress -> !existingLessonIds.contains(progress.getLessonId()))
                .map(progress -> toEvidence(batch, progress, migrationVersion))
                .toList();
        if (additions.isEmpty()) {
            return;
        }

        beforeInsert.run();
        try {
            evidenceRepository.saveAllAndFlush(additions);
        } catch (DataIntegrityViolationException exception) {
            if (!isUniqueViolation(exception)) {
                throw exception;
            }
            throw new ConcurrentEvidenceInsertException(exception);
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

    private LegacyLearningEvidenceEntity toEvidence(
            MigrationBatch batch,
            LessonProgressEntity progress,
            int migrationVersion
    ) {
        String nodeId = batch.mappingByLessonId().get(progress.getLessonId()).getNodeId();
        UserRoadmapNodeEntity state = batch.stateByNodeId().get(nodeId);
        return new LegacyLearningEvidenceEntity(
                stableEvidenceId(batch.enrollment().getOwnerId(), progress.getLessonId(), migrationVersion),
                batch.enrollment().getOwnerId(),
                state.getId(),
                progress.getLessonId(),
                progress.getStatus().name(),
                evidenceJson(progress),
                migrationVersion,
                Instant.now());
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

    private void recoverExactDuplicate(
            String userRoadmapId,
            int migrationVersion,
            ConcurrentEvidenceInsertException collision
    ) {
        Boolean recovered = transactionTemplate.execute(ignored -> {
            MigrationBatch batch = loadBatch(userRoadmapId);
            if (batch.progressByLessonId().isEmpty()) {
                return true;
            }
            Set<String> persisted = evidenceRepository
                    .findAllByOwnerIdAndMigrationVersionAndLessonIdIn(
                            batch.enrollment().getOwnerId(), migrationVersion,
                            batch.progressByLessonId().keySet())
                    .stream().map(LegacyLearningEvidenceEntity::getLessonId)
                    .collect(java.util.stream.Collectors.toSet());
            return persisted.containsAll(batch.progressByLessonId().keySet());
        });
        if (!Boolean.TRUE.equals(recovered)) {
            throw collision.integrityViolation();
        }
    }

    private static boolean isUniqueViolation(DataIntegrityViolationException exception) {
        boolean uniqueViolation = false;
        Set<Throwable> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        Throwable current = exception;
        while (current != null && visited.add(current)) {
            if (current instanceof ConstraintViolationException constraintViolation) {
                uniqueViolation |= constraintViolation.getKind()
                        == ConstraintViolationException.ConstraintKind.UNIQUE;
            }
            if (current instanceof SQLException sqlException) {
                uniqueViolation |= "23505".equals(sqlException.getSQLState())
                        || sqlException.getErrorCode() == 1062;
            }
            String message = current.getMessage();
            if (message != null && UNIQUE_CONSTRAINT_IN_MESSAGE.matcher(message).find()) {
                uniqueViolation = true;
            }
            current = current.getCause();
        }
        return uniqueViolation;
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

    private static final class ConcurrentEvidenceInsertException extends RuntimeException {
        private final DataIntegrityViolationException integrityViolation;

        private ConcurrentEvidenceInsertException(DataIntegrityViolationException integrityViolation) {
            super(integrityViolation);
            this.integrityViolation = integrityViolation;
        }

        private DataIntegrityViolationException integrityViolation() {
            return integrityViolation;
        }
    }
}
