package com.moxiao.studypilot.roadmap.application;

import com.moxiao.studypilot.roadmap.api.RoadmapUpgradeResponse;
import com.moxiao.studypilot.roadmap.domain.AvailabilityStatus;
import com.moxiao.studypilot.roadmap.domain.CompletionStatus;
import com.moxiao.studypilot.roadmap.domain.RoadmapPublicationStatus;
import com.moxiao.studypilot.roadmap.domain.UpgradeStatus;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeEntity;
import com.moxiao.studypilot.roadmap.infrastructure.LegacyLessonRoadmapMappingEntity;
import com.moxiao.studypilot.roadmap.infrastructure.LegacyLessonRoadmapMappingJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapModuleEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapModuleJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodePrerequisiteEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodePrerequisiteJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapTemplateEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapTemplateJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapStageEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapStageJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapUpgradeEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapUpgradeJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapEntity;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapNodeEntity;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapNodeJpaRepository;
import com.moxiao.studypilot.shared.error.ConflictException;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
public class RoadmapUpgradeService {

    private static final String CURRENT = "CURRENT";

    private final RoadmapTemplateJpaRepository templateRepository;
    private final RoadmapNodeJpaRepository nodeRepository;
    private final RoadmapNodePrerequisiteJpaRepository prerequisiteRepository;
    private final RoadmapModuleJpaRepository moduleRepository;
    private final RoadmapStageJpaRepository stageRepository;
    private final LegacyLessonRoadmapMappingJpaRepository legacyMappingRepository;
    private final UserRoadmapJpaRepository enrollmentRepository;
    private final UserRoadmapNodeJpaRepository stateRepository;
    private final RoadmapUpgradeJpaRepository upgradeRepository;
    private final RoadmapEnrollmentService enrollmentService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate upgradeTransaction;

    public RoadmapUpgradeService(
            RoadmapTemplateJpaRepository templateRepository,
            RoadmapNodeJpaRepository nodeRepository,
            RoadmapNodePrerequisiteJpaRepository prerequisiteRepository,
            RoadmapModuleJpaRepository moduleRepository,
            RoadmapStageJpaRepository stageRepository,
            LegacyLessonRoadmapMappingJpaRepository legacyMappingRepository,
            UserRoadmapJpaRepository enrollmentRepository,
            UserRoadmapNodeJpaRepository stateRepository,
            RoadmapUpgradeJpaRepository upgradeRepository,
            RoadmapEnrollmentService enrollmentService,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.templateRepository = templateRepository;
        this.nodeRepository = nodeRepository;
        this.prerequisiteRepository = prerequisiteRepository;
        this.moduleRepository = moduleRepository;
        this.stageRepository = stageRepository;
        this.legacyMappingRepository = legacyMappingRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.stateRepository = stateRepository;
        this.upgradeRepository = upgradeRepository;
        this.enrollmentService = enrollmentService;
        this.objectMapper = objectMapper;
        this.upgradeTransaction = new TransactionTemplate(transactionManager);
        this.upgradeTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.upgradeTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /**
     * Preview creation intentionally locks the current enrollment. This makes the read-and-create
     * operation idempotent even when a client retries the GET concurrently.
     */
    public List<RoadmapUpgradeResponse> previews(String ownerId) {
        return executeWithBoundedLockRetry(() -> previewsInTransaction(ownerId));
    }

    private List<RoadmapUpgradeResponse> previewsInTransaction(String ownerId) {
        UserRoadmapEntity sourceEnrollment = enrollmentRepository
                .findByOwnerIdAndActiveSlotForUpdate(ownerId, CURRENT)
                .orElseThrow(() -> new ResourceNotFoundException("当前学习路线不存在"));
        RoadmapTemplateEntity source = templateRepository.findById(sourceEnrollment.getTemplateId())
                .orElseThrow(() -> new IllegalStateException("当前路线模板不存在"));
        List<RoadmapTemplateEntity> publishedVersions = lockPublishedVersions(source.getRoadmapCode());
        RoadmapTemplateEntity latest = publishedVersions.isEmpty() ? null : publishedVersions.get(0);
        RoadmapTemplateEntity target = latest != null
                && latest.getTemplateVersion() > source.getTemplateVersion() ? latest : null;
        if (target == null) {
            return List.of();
        }

        String idempotencyKey = idempotencyKey(sourceEnrollment.getId(), target.getId());
        RoadmapUpgradeEntity preview = upgradeRepository
                .findByOwnerIdAndIdempotencyKey(ownerId, idempotencyKey)
                .orElseGet(() -> createPreview(ownerId, sourceEnrollment, source, target, idempotencyKey));
        return List.of(toResponse(preview));
    }

    public RoadmapUpgradeResponse confirm(String ownerId, String upgradeId) {
        return executeWithBoundedLockRetry(() -> confirmInTransaction(ownerId, upgradeId));
    }

    private RoadmapUpgradeResponse confirmInTransaction(String ownerId, String upgradeId) {
        String sourceEnrollmentId = upgradeRepository
                .findUserRoadmapIdByOwnerIdAndId(ownerId, upgradeId)
                .orElseThrow(() -> new ResourceNotFoundException("路线升级预览不存在"));
        // Global order: enrollment -> all published target-template rows -> upgrade preview.
        UserRoadmapEntity sourceEnrollment = enrollmentRepository.findByIdForUpdate(sourceEnrollmentId)
                .filter(enrollment -> enrollment.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new ResourceNotFoundException("原路线绑定不存在"));
        RoadmapTemplateEntity source = templateRepository.findById(sourceEnrollment.getTemplateId())
                .orElseThrow(() -> new IllegalStateException("升级记录的原模板不存在"));
        List<RoadmapTemplateEntity> publishedVersions = lockPublishedVersions(source.getRoadmapCode());
        RoadmapUpgradeEntity upgrade = upgradeRepository
                .findByOwnerIdAndIdForUpdate(ownerId, upgradeId)
                .orElseThrow(() -> new ResourceNotFoundException("路线升级预览不存在"));
        UpgradeDiff diff = readDiff(upgrade.getDiffJson());
        if (upgrade.getStatus() == UpgradeStatus.COMPLETED) {
            return response(upgrade, diff);
        }
        if (!diff.manualReviewNodeCodes().isEmpty()) {
            throw new ConflictException("路线升级包含需要人工映射的节点，当前版本不能确认");
        }
        if (!CURRENT.equals(sourceEnrollment.getActiveSlot())) {
            throw new ConflictException("原路线已不再是当前路线");
        }
        RoadmapTemplateEntity target = publishedVersions.stream()
                .filter(template -> template.getId().equals(upgrade.getTargetTemplateId()))
                .findFirst()
                .orElseThrow(() -> new ConflictException("目标路线版本已不可用"));
        if (target.getTemplateVersion() <= source.getTemplateVersion()
                || !publishedVersions.get(0).getId().equals(target.getId())) {
            throw new ConflictException("路线升级预览已过期，请重新生成");
        }

        Instant now = Instant.now();
        sourceEnrollment.supersede(now);
        enrollmentRepository.saveAndFlush(sourceEnrollment); // release the unique CURRENT slot first

        UserRoadmapEntity targetEnrollment = new UserRoadmapEntity(
                UUID.randomUUID().toString(), ownerId, target.getId(), now);
        enrollmentRepository.saveAndFlush(targetEnrollment);
        initializeTargetStates(ownerId, sourceEnrollment, targetEnrollment, target, diff, now);
        enrollmentService.recalculateAvailability(targetEnrollment.getId());
        upgrade.complete(now);
        return response(upgrade, diff);
    }

    private RoadmapUpgradeEntity createPreview(
            String ownerId,
            UserRoadmapEntity sourceEnrollment,
            RoadmapTemplateEntity source,
            RoadmapTemplateEntity target,
            String idempotencyKey
    ) {
        UpgradeDiff diff = compare(source.getId(), target.getId());
        RoadmapUpgradeEntity preview = new RoadmapUpgradeEntity(
                UUID.randomUUID().toString(), ownerId, sourceEnrollment.getId(), target.getId(),
                UpgradeStatus.PREVIEW, writeDiff(diff), idempotencyKey, Instant.now(), null);
        return upgradeRepository.saveAndFlush(preview);
    }

    private UpgradeDiff compare(String sourceTemplateId, String targetTemplateId) {
        List<RoadmapNodeEntity> sourceNodes = nodeRepository
                .findAllByTemplateIdOrderByStageIdAscNodeOrderAsc(sourceTemplateId);
        List<RoadmapNodeEntity> targetNodes = nodeRepository
                .findAllByTemplateIdOrderByStageIdAscNodeOrderAsc(targetTemplateId);
        Map<String, RoadmapNodeEntity> sourceByCode = byCode(sourceNodes);
        Map<String, RoadmapNodeEntity> targetByCode = byCode(targetNodes);
        Map<String, RoadmapNodeEntity> sourceById = sourceNodes.stream().collect(Collectors.toMap(
                RoadmapNodeEntity::getId, node -> node));
        Map<String, RoadmapNodeEntity> targetById = targetNodes.stream().collect(Collectors.toMap(
                RoadmapNodeEntity::getId, node -> node));
        ExplicitMappings mappings = explicitMappings(
                sourceTemplateId, targetTemplateId, sourceById, targetById);
        Map<String, Set<String>> sourcePrerequisites = prerequisitesByNodeCode(sourceNodes,
                prerequisiteRepository.findAllByTemplateId(sourceTemplateId));
        Map<String, Set<String>> targetPrerequisites = prerequisitesByNodeCode(targetNodes,
                prerequisiteRepository.findAllByTemplateId(targetTemplateId));

        List<String> unchanged = new ArrayList<>();
        List<String> added = new ArrayList<>();
        List<String> manualReview = new ArrayList<>();
        Map<String, String> inheritedNodeMappings = new LinkedHashMap<>();
        Set<String> matchedSourceIds = new HashSet<>();
        for (RoadmapNodeEntity target : targetNodes) {
            String sourceId = mappings.sourceIdByTargetId().get(target.getId());
            RoadmapNodeEntity source = sourceId == null ? null : sourceById.get(sourceId);
            if (source == null) {
                if (mappings.ambiguousTargetIds().contains(target.getId())) {
                    manualReview.add(target.getNodeCode());
                } else {
                    added.add(target.getNodeCode());
                }
            } else if (equivalentCompletionContract(source, target)
                    && equivalentPrerequisites(source, target, sourcePrerequisites,
                    targetPrerequisites, sourceByCode, targetByCode, mappings)) {
                unchanged.add(target.getNodeCode());
                inheritedNodeMappings.put(target.getNodeCode(), source.getNodeCode());
                matchedSourceIds.add(source.getId());
            } else {
                manualReview.add(target.getNodeCode());
                matchedSourceIds.add(source.getId());
            }
        }
        List<String> removed = sourceNodes.stream()
                .filter(node -> !matchedSourceIds.contains(node.getId()))
                .map(RoadmapNodeEntity::getNodeCode)
                .toList();
        ModuleDiff moduleDiff = compareModules(sourceTemplateId, targetTemplateId);
        return new UpgradeDiff(unchanged, added, removed, manualReview, inheritedNodeMappings,
                moduleDiff.added(), moduleDiff.removed(), moduleDiff.changed());
    }

    private boolean equivalentCompletionContract(RoadmapNodeEntity left, RoadmapNodeEntity right) {
        return left.isRequiredNode() == right.isRequiredNode()
                && left.getTitle().equals(right.getTitle())
                && semanticallyEqualJson(canonicalJson(left.getObjectivesJson()),
                        canonicalJson(right.getObjectivesJson()))
                && semanticallyEqualJson(canonicalJson(left.getHighFrequencyJson()),
                        canonicalJson(right.getHighFrequencyJson()))
                && semanticallyEqualJson(canonicalJson(left.getCommonMistakesJson()),
                        canonicalJson(right.getCommonMistakesJson()))
                && semanticallyEqualJson(canonicalJson(left.getSearchKeywordsJson()),
                        canonicalJson(right.getSearchKeywordsJson()))
                && semanticallyEqualJson(
                        canonicalJson(left.getArtifactRequirementJson()),
                        canonicalJson(right.getArtifactRequirementJson()))
                && semanticallyEqualJson(
                        canonicalJson(left.getQuizBlueprintJson()),
                        canonicalJson(right.getQuizBlueprintJson()));
    }

    private ExplicitMappings explicitMappings(
            String sourceTemplateId,
            String targetTemplateId,
            Map<String, RoadmapNodeEntity> sourceById,
            Map<String, RoadmapNodeEntity> targetById
    ) {
        Map<String, String> sourceNodeByLesson = legacyMappingRepository
                .findAllByTemplateId(sourceTemplateId).stream().collect(Collectors.toMap(
                        LegacyLessonRoadmapMappingEntity::getLessonId,
                        LegacyLessonRoadmapMappingEntity::getNodeId));
        Map<String, String> targetNodeByLesson = legacyMappingRepository
                .findAllByTemplateId(targetTemplateId).stream().collect(Collectors.toMap(
                        LegacyLessonRoadmapMappingEntity::getLessonId,
                        LegacyLessonRoadmapMappingEntity::getNodeId));
        Map<String, Set<String>> targetsBySource = new HashMap<>();
        Map<String, Set<String>> sourcesByTarget = new HashMap<>();
        for (Map.Entry<String, String> entry : sourceNodeByLesson.entrySet()) {
            String targetId = targetNodeByLesson.get(entry.getKey());
            if (targetId == null) {
                continue;
            }
            String sourceId = entry.getValue();
            if (!sourceById.containsKey(sourceId) || !targetById.containsKey(targetId)) {
                throw new IllegalStateException("旧课程映射引用了模板外节点");
            }
            targetsBySource.computeIfAbsent(sourceId, ignored -> new HashSet<>()).add(targetId);
            sourcesByTarget.computeIfAbsent(targetId, ignored -> new HashSet<>()).add(sourceId);
        }
        Map<String, String> sourceIdByTargetId = new HashMap<>();
        Set<String> ambiguousTargetIds = new HashSet<>();
        for (Map.Entry<String, Set<String>> entry : sourcesByTarget.entrySet()) {
            String targetId = entry.getKey();
            Set<String> sourceIds = entry.getValue();
            if (sourceIds.size() != 1) {
                ambiguousTargetIds.add(targetId);
                continue;
            }
            String sourceId = sourceIds.iterator().next();
            if (targetsBySource.getOrDefault(sourceId, Set.of()).size() != 1) {
                ambiguousTargetIds.add(targetId);
                continue;
            }
            sourceIdByTargetId.put(targetId, sourceId);
        }
        return new ExplicitMappings(sourceIdByTargetId, ambiguousTargetIds);
    }

    private boolean equivalentPrerequisites(
            RoadmapNodeEntity source,
            RoadmapNodeEntity target,
            Map<String, Set<String>> sourcePrerequisites,
            Map<String, Set<String>> targetPrerequisites,
            Map<String, RoadmapNodeEntity> sourceByCode,
            Map<String, RoadmapNodeEntity> targetByCode,
            ExplicitMappings mappings
    ) {
        Set<String> expectedTargetPrerequisiteCodes = new HashSet<>();
        for (String sourceCode : sourcePrerequisites.getOrDefault(source.getNodeCode(), Set.of())) {
            RoadmapNodeEntity sourcePrerequisite = sourceByCode.get(sourceCode);
            if (sourcePrerequisite == null) {
                return false;
            }
            String targetId = mappings.targetIdBySourceId().get(sourcePrerequisite.getId());
            RoadmapNodeEntity mappedTarget = targetByCode.values().stream()
                    .filter(node -> node.getId().equals(targetId)).findFirst().orElse(null);
            if (mappedTarget == null) {
                return false;
            }
            expectedTargetPrerequisiteCodes.add(mappedTarget.getNodeCode());
        }
        return expectedTargetPrerequisiteCodes.equals(
                targetPrerequisites.getOrDefault(target.getNodeCode(), Set.of()));
    }

    private ModuleDiff compareModules(String sourceTemplateId, String targetTemplateId) {
        Map<String, String> sourceStageCodes = stageRepository
                .findAllByTemplateIdOrderByStageOrderAsc(sourceTemplateId).stream()
                .collect(Collectors.toMap(RoadmapStageEntity::getId, RoadmapStageEntity::getStageCode));
        Map<String, String> targetStageCodes = stageRepository
                .findAllByTemplateIdOrderByStageOrderAsc(targetTemplateId).stream()
                .collect(Collectors.toMap(RoadmapStageEntity::getId, RoadmapStageEntity::getStageCode));
        Map<String, RoadmapModuleEntity> source = modulesByCode(sourceTemplateId);
        Map<String, RoadmapModuleEntity> target = modulesByCode(targetTemplateId);
        int added = (int) target.keySet().stream().filter(code -> !source.containsKey(code)).count();
        int removed = (int) source.keySet().stream().filter(code -> !target.containsKey(code)).count();
        int changed = (int) target.entrySet().stream()
                .filter(entry -> source.containsKey(entry.getKey()))
                .filter(entry -> moduleChanged(source.get(entry.getKey()), entry.getValue(),
                        sourceStageCodes, targetStageCodes))
                .count();
        return new ModuleDiff(added, removed, changed);
    }

    private Map<String, RoadmapModuleEntity> modulesByCode(String templateId) {
        Map<String, RoadmapModuleEntity> result = new LinkedHashMap<>();
        for (RoadmapModuleEntity module : moduleRepository
                .findAllByTemplateIdOrderByStageIdAscModuleOrderAsc(templateId)) {
            if (result.put(module.getModuleCode(), module) != null) {
                throw new IllegalStateException("路线模板中存在重复模块编码: " + module.getModuleCode());
            }
        }
        return result;
    }

    private boolean moduleChanged(
            RoadmapModuleEntity source,
            RoadmapModuleEntity target,
            Map<String, String> sourceStageCodes,
            Map<String, String> targetStageCodes
    ) {
        return source.getModuleOrder() != target.getModuleOrder()
                || !source.getTitle().equals(target.getTitle())
                || !source.getDescription().equals(target.getDescription())
                || !java.util.Objects.equals(sourceStageCodes.get(source.getStageId()),
                targetStageCodes.get(target.getStageId()));
    }

    /** Objects are unordered, arrays remain ordered, and JSON numbers compare by numeric value. */
    private boolean semanticallyEqualJson(JsonNode left, JsonNode right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left.isNumber() && right.isNumber()) {
            return left.decimalValue().stripTrailingZeros()
                    .compareTo(right.decimalValue().stripTrailingZeros()) == 0;
        }
        if (left.isObject() && right.isObject()) {
            Set<String> leftNames = Set.copyOf(left.propertyNames());
            Set<String> rightNames = Set.copyOf(right.propertyNames());
            return leftNames.equals(rightNames) && leftNames.stream()
                    .allMatch(name -> semanticallyEqualJson(left.get(name), right.get(name)));
        }
        if (left.isArray() && right.isArray()) {
            if (left.size() != right.size()) {
                return false;
            }
            for (int index = 0; index < left.size(); index++) {
                if (!semanticallyEqualJson(left.get(index), right.get(index))) {
                    return false;
                }
            }
            return true;
        }
        return left.equals(right);
    }

    private List<RoadmapTemplateEntity> lockPublishedVersions(String roadmapCode) {
        return templateRepository.findPublishedVersionsForUpgrade(
                roadmapCode, RoadmapPublicationStatus.PUBLISHED);
    }

    private <T> T executeWithBoundedLockRetry(Supplier<T> work) {
        // Retry only transient lock acquisition failures. Integrity violations and every other
        // exception propagate unchanged, so idempotency conflicts fail closed.
        CannotAcquireLockException lastFailure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                T result = upgradeTransaction.execute(status -> work.get());
                if (result == null) {
                    throw new IllegalStateException("路线升级事务未返回结果");
                }
                return result;
            } catch (CannotAcquireLockException exception) {
                lastFailure = exception;
                Thread.yield();
            }
        }
        if (lastFailure == null) {
            throw new IllegalStateException("路线升级锁重试未执行");
        }
        throw lastFailure;
    }

    private JsonNode canonicalJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("路线节点完成契约不是合法 JSON", exception);
        }
    }

    private Map<String, Set<String>> prerequisitesByNodeCode(
            List<RoadmapNodeEntity> nodes,
            List<RoadmapNodePrerequisiteEntity> prerequisites
    ) {
        Map<String, String> codeById = nodes.stream().collect(Collectors.toMap(
                RoadmapNodeEntity::getId, RoadmapNodeEntity::getNodeCode));
        Map<String, Set<String>> result = new HashMap<>();
        for (RoadmapNodePrerequisiteEntity edge : prerequisites) {
            String nodeCode = codeById.get(edge.getNodeId());
            String prerequisiteCode = codeById.get(edge.getPrerequisiteNodeId());
            if (nodeCode == null || prerequisiteCode == null) {
                throw new IllegalStateException("路线先修关系引用了模板外节点");
            }
            result.computeIfAbsent(nodeCode, ignored -> new HashSet<>()).add(prerequisiteCode);
        }
        return result;
    }

    private void initializeTargetStates(
            String ownerId,
            UserRoadmapEntity sourceEnrollment,
            UserRoadmapEntity targetEnrollment,
            RoadmapTemplateEntity target,
            UpgradeDiff diff,
            Instant now
    ) {
        List<RoadmapNodeEntity> targetNodes = nodeRepository
                .findAllByTemplateIdOrderByStageIdAscNodeOrderAsc(target.getId());
        Set<String> nodesWithPrerequisites = prerequisiteRepository.findAllByTemplateId(target.getId())
                .stream().map(RoadmapNodePrerequisiteEntity::getNodeId).collect(Collectors.toSet());
        Map<String, RoadmapNodeEntity> sourceNodesById = nodeRepository
                .findAllByTemplateIdOrderByStageIdAscNodeOrderAsc(sourceEnrollment.getTemplateId())
                .stream().collect(Collectors.toMap(RoadmapNodeEntity::getId, node -> node));
        Map<String, UserRoadmapNodeEntity> completedSourceByCode = stateRepository
                .findAllByUserRoadmapId(sourceEnrollment.getId()).stream()
                .filter(state -> state.getCompletionStatus() == CompletionStatus.COMPLETED)
                .filter(state -> sourceNodesById.containsKey(state.getNodeId()))
                .collect(Collectors.toMap(
                        state -> sourceNodesById.get(state.getNodeId()).getNodeCode(), state -> state));

        List<UserRoadmapNodeEntity> targetStates = targetNodes.stream().map(node -> {
            UserRoadmapNodeEntity state = new UserRoadmapNodeEntity(
                    stableStateId(targetEnrollment.getId(), node.getId()), targetEnrollment.getId(),
                    node.getId(), ownerId, target.getId(),
                    nodesWithPrerequisites.contains(node.getId())
                            ? AvailabilityStatus.LOCKED : AvailabilityStatus.AVAILABLE,
                    artifactRequired(node), now);
            String sourceCode = diff.inheritedNodeMappings().get(node.getNodeCode());
            UserRoadmapNodeEntity sourceState = completedSourceByCode.get(sourceCode);
            if (sourceState != null) {
                state.carryCompletedFromUpgrade(sourceState, now);
            }
            return state;
        }).toList();
        stateRepository.saveAll(targetStates);
        stateRepository.flush();
    }

    private boolean artifactRequired(RoadmapNodeEntity node) {
        JsonNode required = canonicalJson(node.getArtifactRequirementJson()).get("required");
        if (required == null || !required.isBoolean()) {
            throw new IllegalStateException("路线节点产物要求配置无效: " + node.getId());
        }
        return required.asBoolean();
    }

    private RoadmapUpgradeResponse toResponse(RoadmapUpgradeEntity upgrade) {
        return response(upgrade, readDiff(upgrade.getDiffJson()));
    }

    private RoadmapUpgradeResponse response(RoadmapUpgradeEntity upgrade, UpgradeDiff diff) {
        UserRoadmapEntity sourceEnrollment = enrollmentRepository.findById(upgrade.getUserRoadmapId())
                .orElseThrow(() -> new IllegalStateException("升级记录的原路线不存在"));
        RoadmapTemplateEntity source = templateRepository.findById(sourceEnrollment.getTemplateId())
                .orElseThrow(() -> new IllegalStateException("升级记录的原模板不存在"));
        RoadmapTemplateEntity target = templateRepository.findById(upgrade.getTargetTemplateId())
                .orElseThrow(() -> new IllegalStateException("升级记录的目标模板不存在"));
        return new RoadmapUpgradeResponse(
                upgrade.getId(), source.getTemplateVersion(), target.getTemplateVersion(),
                upgrade.getStatus().name(), diff.unchangedNodeCodes(), diff.addedNodeCodes(),
                diff.removedNodeCodes(), diff.manualReviewNodeCodes(), diff.addedModuleCount(),
                diff.removedModuleCount(), diff.changedModuleCount());
    }

    private String writeDiff(UpgradeDiff diff) {
        try {
            return objectMapper.writeValueAsString(diff);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("无法保存路线升级差异", exception);
        }
    }

    private UpgradeDiff readDiff(String json) {
        try {
            return objectMapper.readValue(json, UpgradeDiff.class);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("路线升级差异损坏", exception);
        }
    }

    private static Map<String, RoadmapNodeEntity> byCode(List<RoadmapNodeEntity> nodes) {
        Map<String, RoadmapNodeEntity> result = new LinkedHashMap<>();
        for (RoadmapNodeEntity node : nodes) {
            if (result.put(node.getNodeCode(), node) != null) {
                throw new IllegalStateException("路线模板中存在重复节点编码: " + node.getNodeCode());
            }
        }
        return result;
    }

    private static String idempotencyKey(String enrollmentId, String targetTemplateId) {
        return "roadmap-upgrade:" + enrollmentId + ":" + targetTemplateId;
    }

    private static String stableStateId(String enrollmentId, String nodeId) {
        return UUID.nameUUIDFromBytes((enrollmentId + ":" + nodeId).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private record UpgradeDiff(
            List<String> unchangedNodeCodes,
            List<String> addedNodeCodes,
            List<String> removedNodeCodes,
            List<String> manualReviewNodeCodes,
            Map<String, String> inheritedNodeMappings,
            int addedModuleCount,
            int removedModuleCount,
            int changedModuleCount
    ) {
        private UpgradeDiff {
            inheritedNodeMappings = inheritedNodeMappings == null ? Map.of() : inheritedNodeMappings;
        }
    }

    private record ExplicitMappings(
            Map<String, String> sourceIdByTargetId,
            Set<String> ambiguousTargetIds
    ) {
        private Map<String, String> targetIdBySourceId() {
            return sourceIdByTargetId.entrySet().stream().collect(Collectors.toMap(
                    Map.Entry::getValue, Map.Entry::getKey));
        }
    }

    private record ModuleDiff(int added, int removed, int changed) { }
}
