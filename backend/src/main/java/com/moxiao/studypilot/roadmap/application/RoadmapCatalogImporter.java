package com.moxiao.studypilot.roadmap.application;

import com.moxiao.studypilot.course.infrastructure.LessonJpaRepository;
import com.moxiao.studypilot.roadmap.domain.RoadmapPublicationStatus;
import com.moxiao.studypilot.roadmap.infrastructure.LegacyLessonRoadmapMappingEntity;
import com.moxiao.studypilot.roadmap.infrastructure.LegacyLessonRoadmapMappingJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodePrerequisiteEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodePrerequisiteJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapStageEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapStageJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapTemplateEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapTemplateJpaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

@Component
public class RoadmapCatalogImporter {

    private static final String CATALOG_PATH = "roadmaps/studypilot-java-ai-v1.json";
    private static final String ROADMAP_CODE = "studypilot-java-ai";
    private static final int VERSION = 1;

    private final RoadmapTemplateJpaRepository templateRepository;
    private final RoadmapStageJpaRepository stageRepository;
    private final RoadmapNodeJpaRepository nodeRepository;
    private final RoadmapNodePrerequisiteJpaRepository prerequisiteRepository;
    private final LessonJpaRepository lessonRepository;
    private final LegacyLessonRoadmapMappingJpaRepository legacyMappingRepository;
    private final ObjectMapper objectMapper;
    private final RoadmapCatalogValidator graphValidator = new RoadmapCatalogValidator();
    private final TransactionTemplate transactionTemplate;
    private final Runnable beforeInsert;
    private final UnaryOperator<String> checksumTransform;

    @Autowired
    public RoadmapCatalogImporter(
            RoadmapTemplateJpaRepository templateRepository,
            RoadmapStageJpaRepository stageRepository,
            RoadmapNodeJpaRepository nodeRepository,
            RoadmapNodePrerequisiteJpaRepository prerequisiteRepository,
            LessonJpaRepository lessonRepository,
            LegacyLessonRoadmapMappingJpaRepository legacyMappingRepository,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this(templateRepository, stageRepository, nodeRepository, prerequisiteRepository,
                lessonRepository, legacyMappingRepository,
                objectMapper, transactionManager, () -> { }, UnaryOperator.identity());
    }

    RoadmapCatalogImporter(
            RoadmapTemplateJpaRepository templateRepository,
            RoadmapStageJpaRepository stageRepository,
            RoadmapNodeJpaRepository nodeRepository,
            RoadmapNodePrerequisiteJpaRepository prerequisiteRepository,
            LessonJpaRepository lessonRepository,
            LegacyLessonRoadmapMappingJpaRepository legacyMappingRepository,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            Runnable beforeInsert,
            UnaryOperator<String> checksumTransform
    ) {
        this.templateRepository = templateRepository;
        this.stageRepository = stageRepository;
        this.nodeRepository = nodeRepository;
        this.prerequisiteRepository = prerequisiteRepository;
        this.lessonRepository = lessonRepository;
        this.legacyMappingRepository = legacyMappingRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.beforeInsert = beforeInsert;
        this.checksumTransform = checksumTransform;
    }

    public void importCatalog() {
        ParsedCatalog parsed = readCatalog();
        validateCatalog(parsed.catalog());

        try {
            transactionTemplate.executeWithoutResult(status -> importInTransaction(parsed));
        } catch (ConcurrentTemplateInsertException collision) {
            recoverConcurrentInsert(parsed, collision);
        }
    }

    private void importInTransaction(ParsedCatalog parsed) {
        var existing = templateRepository.findByRoadmapCodeAndTemplateVersion(
                parsed.catalog().roadmapCode(), parsed.catalog().version());
        if (existing.isPresent()) {
            verifyChecksum(existing.get(), parsed.checksum());
            persistLegacyMappings(parsed.catalog(), existing.get().getId());
            return;
        }

        beforeInsert.run();
        try {
            persistTemplate(parsed.catalog(), parsed.checksum());
            templateRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw new ConcurrentTemplateInsertException(exception);
        }
        persistChildren(parsed.catalog());
        persistLegacyMappings(parsed.catalog(), templateId(parsed.catalog()));
    }

    private void recoverConcurrentInsert(ParsedCatalog parsed, ConcurrentTemplateInsertException collision) {
        Boolean recovered = transactionTemplate.execute(status -> templateRepository
                .findByRoadmapCodeAndTemplateVersion(parsed.catalog().roadmapCode(), parsed.catalog().version())
                .map(existing -> {
                    verifyChecksum(existing, parsed.checksum());
                    persistLegacyMappings(parsed.catalog(), existing.getId());
                    return true;
                })
                .orElse(false));
        if (!Boolean.TRUE.equals(recovered)) {
            throw collision.integrityViolation();
        }
    }

    private static void verifyChecksum(RoadmapTemplateEntity existing, String checksum) {
        if (!existing.getContentChecksum().equals(checksum)) {
            throw new IllegalStateException("已发布路线版本不可修改");
        }
    }

    private ParsedCatalog readCatalog() {
        try (var input = new ClassPathResource(CATALOG_PATH).getInputStream()) {
            JsonNode root = objectMapper.readTree(input);
            Catalog catalog = objectMapper.treeToValue(root, Catalog.class);
            String checksum = RoadmapCatalogChecksum.sha256(objectMapper, root);
            return new ParsedCatalog(catalog, checksumTransform.apply(checksum));
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取内置路线目录", exception);
        }
    }

    private void validateCatalog(Catalog catalog) {
        if (catalog == null) {
            throw new IllegalArgumentException("路线目录不能为空");
        }
        require(ROADMAP_CODE.equals(catalog.roadmapCode()), "roadmapCode 必须为 " + ROADMAP_CODE);
        require(catalog.version() == VERSION, "version 必须为 1");
        require("StudyPilot Java + AI 学习路线".equals(catalog.title()), "路线标题不正确");
        require(catalog.publicationStatus() == RoadmapPublicationStatus.PUBLISHED, "路线必须为 PUBLISHED");
        requireText(catalog.description(), "description");
        require(catalog.stages() != null && catalog.stages().size() == 12, "路线必须包含 12 个 stage");

        Set<String> stageCodes = new HashSet<>();
        Set<Integer> stageOrders = new HashSet<>();
        Set<String> nodeCodes = new HashSet<>();
        List<RoadmapCatalogValidator.Node> graphNodes = new ArrayList<>();
        int edgeCount = 0;

        for (Stage stage : catalog.stages()) {
            require(stage != null, "stage 不能为空");
            requireText(stage.stageCode(), "stageCode");
            require(stageCodes.add(stage.stageCode()), "stageCode 重复: " + stage.stageCode());
            require(stage.stageOrder() > 0 && stageOrders.add(stage.stageOrder()),
                    "stage order 无效或重复: " + stage.stageOrder());
            requireText(stage.title(), "stage title");
            requireText(stage.description(), "stage description");
            requireText(stage.graduationProjectTitle(), "graduationProjectTitle");
            require(stage.nodes() != null && !stage.nodes().isEmpty(), "stage nodes 不能为空: " + stage.stageCode());

            Set<Integer> nodeOrders = new HashSet<>();
            for (Node node : stage.nodes()) {
                validateNode(node, nodeOrders);
                require(nodeCodes.add(node.code()), "node code 重复: " + node.code());
                graphNodes.add(new RoadmapCatalogValidator.Node(node.code(), node.prerequisites()));
                edgeCount += node.prerequisites().size();
            }
        }

        require(nodeCodes.size() == 64, "路线必须包含 64 个 node");
        require(edgeCount == 79, "路线必须包含 79 条 prerequisite");
        graphValidator.validate(graphNodes);

        require(catalog.legacyLessonMappings() != null && !catalog.legacyLessonMappings().isEmpty(),
                "legacyLessonMappings 不能为空");
        Set<String> lessonIds = new HashSet<>();
        for (LegacyLessonMapping mapping : catalog.legacyLessonMappings()) {
            require(mapping != null, "legacyLessonMapping 不能为空");
            requireText(mapping.lessonId(), "legacy lessonId");
            require(lessonIds.add(mapping.lessonId()), "legacy lessonId 重复: " + mapping.lessonId());
            requireText(mapping.nodeCode(), "legacy nodeCode");
            require(nodeCodes.contains(mapping.nodeCode()), "legacy nodeCode 不存在: " + mapping.nodeCode());
        }
    }

    private void validateNode(Node node, Set<Integer> nodeOrders) {
        require(node != null, "node 不能为空");
        requireText(node.code(), "node code");
        require(node.nodeOrder() > 0 && nodeOrders.add(node.nodeOrder()),
                "node order 无效或重复: " + node.nodeOrder());
        requireText(node.title(), "node title");
        requireTextList(node.objectives(), "objectives", node.code());
        requireTextList(node.highFrequency(), "highFrequency", node.code());
        requireTextList(node.commonMistakes(), "commonMistakes", node.code());
        requireTextList(node.searchKeywords(), "searchKeywords", node.code());
        require(node.artifactRequirement() != null, "artifactRequirement 不能为空: " + node.code());
        requireText(node.artifactRequirement().description(), "artifactRequirement description");
        require(Set.of("PRESENCE", "AI_RUBRIC").contains(node.artifactRequirement().evaluationMode()),
                "artifact evaluationMode 无效: " + node.code());
        require(node.quizBlueprint() != null && node.quizBlueprint().size() == 5,
                "quizBlueprint 必须精确包含 5 项: " + node.code());
        requireTextList(node.quizBlueprint(), "quizBlueprint", node.code());
        require(node.estimatedMinutes() >= 60 && node.estimatedMinutes() <= 120,
                "estimatedMinutes 超出范围: " + node.code());
        require(node.practiceMinutes() >= 30 && node.practiceMinutes() <= 120,
                "practiceMinutes 超出范围: " + node.code());
        require(Set.of("EASY", "MEDIUM", "HARD").contains(node.difficulty()),
                "difficulty 无效: " + node.code());
        require(node.prerequisites() != null, "prerequisites 不能为空: " + node.code());
    }

    private void persistTemplate(Catalog catalog, String checksum) {
        String templateId = templateId(catalog);
        Instant now = Instant.now();
        templateRepository.save(new RoadmapTemplateEntity(
                templateId,
                catalog.roadmapCode(),
                catalog.version(),
                catalog.title(),
                catalog.description(),
                catalog.publicationStatus(),
                checksum,
                now
        ));
    }

    private void persistChildren(Catalog catalog) {
        String templateId = templateId(catalog);
        for (Stage stage : catalog.stages()) {
            String stageId = templateId + "-" + stage.stageCode();
            stageRepository.save(new RoadmapStageEntity(
                    stageId, templateId, stage.stageCode(), stage.stageOrder(), stage.title(),
                    stage.description(), stage.graduationProjectTitle()));
        }

        for (Stage stage : catalog.stages()) {
            String stageId = templateId + "-" + stage.stageCode();
            for (Node node : stage.nodes()) {
                nodeRepository.save(new RoadmapNodeEntity(
                        nodeId(templateId, node.code()), templateId, stageId, node.code(), node.nodeOrder(),
                        node.title(), writeJson(node.objectives()), writeJson(node.highFrequency()),
                        writeJson(node.commonMistakes()), writeJson(node.searchKeywords()),
                        writeJson(node.artifactRequirement()), writeJson(node.quizBlueprint()),
                        node.estimatedMinutes(), node.practiceMinutes(), node.difficulty(), node.required()));
            }
        }

        for (Stage stage : catalog.stages()) {
            for (Node node : stage.nodes()) {
                String nodeId = nodeId(templateId, node.code());
                for (String prerequisite : node.prerequisites()) {
                    String prerequisiteNodeId = nodeId(templateId, prerequisite);
                    String id = UUID.nameUUIDFromBytes(
                            (nodeId + "--" + prerequisiteNodeId).getBytes(StandardCharsets.UTF_8)).toString();
                    prerequisiteRepository.save(new RoadmapNodePrerequisiteEntity(
                            id, templateId, nodeId, prerequisiteNodeId));
                }
            }
        }
    }

    private void persistLegacyMappings(Catalog catalog, String templateId) {
        Set<String> lessonIds = catalog.legacyLessonMappings().stream()
                .map(LegacyLessonMapping::lessonId)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> existingLessonIds = lessonRepository.findAllById(lessonIds).stream()
                .map(com.moxiao.studypilot.course.infrastructure.LessonEntity::getId)
                .collect(java.util.stream.Collectors.toSet());
        for (LegacyLessonMapping mapping : catalog.legacyLessonMappings()) {
            if (!existingLessonIds.contains(mapping.lessonId())) {
                throw new IllegalStateException("旧课时不存在: " + mapping.lessonId());
            }
        }

        Map<String, RoadmapNodeEntity> nodesByCode = nodeRepository
                .findAllByTemplateIdOrderByStageIdAscNodeOrderAsc(templateId).stream()
                .collect(java.util.stream.Collectors.toMap(RoadmapNodeEntity::getNodeCode, node -> node));
        List<LegacyLessonRoadmapMappingEntity> mappings = new ArrayList<>();
        for (LegacyLessonMapping mapping : catalog.legacyLessonMappings()) {
            RoadmapNodeEntity node = nodesByCode.get(mapping.nodeCode());
            if (node == null) {
                throw new IllegalStateException("路线节点不存在: " + mapping.nodeCode());
            }
            mappings.add(new LegacyLessonRoadmapMappingEntity(
                    mapping.lessonId(), templateId, node.getId()));
        }
        legacyMappingRepository.saveAll(mappings);
        legacyMappingRepository.flush();
    }

    private static String templateId(Catalog catalog) {
        return catalog.roadmapCode() + "-v" + catalog.version();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("无法序列化路线节点内容", exception);
        }
    }

    private static String nodeId(String templateId, String nodeCode) {
        return templateId + "-" + nodeCode;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void requireText(String value, String field) {
        require(value != null && !value.isBlank(), field + " 不能为空");
    }

    private static void requireTextList(List<String> values, String field, String nodeCode) {
        require(values != null && !values.isEmpty(), field + " 不能为空: " + nodeCode);
        require(values.stream().allMatch(value -> value != null && !value.isBlank()),
                field + " 不能含空项: " + nodeCode);
    }

    private record ParsedCatalog(Catalog catalog, String checksum) {
    }

    private record Catalog(
            String roadmapCode,
            int version,
            String title,
            String description,
            RoadmapPublicationStatus publicationStatus,
            List<Stage> stages,
            List<LegacyLessonMapping> legacyLessonMappings
    ) {
    }

    private record Stage(
            String stageCode,
            int stageOrder,
            String title,
            String description,
            String graduationProjectTitle,
            List<Node> nodes
    ) {
    }

    private record Node(
            String code,
            int nodeOrder,
            String title,
            List<String> objectives,
            List<String> highFrequency,
            List<String> commonMistakes,
            List<String> searchKeywords,
            ArtifactRequirement artifactRequirement,
            List<String> quizBlueprint,
            int estimatedMinutes,
            int practiceMinutes,
            String difficulty,
            boolean required,
            List<String> prerequisites
    ) {
    }

    private record ArtifactRequirement(boolean required, String evaluationMode, String description) {
    }

    private record LegacyLessonMapping(String lessonId, String nodeCode) {
    }

    private static final class ConcurrentTemplateInsertException extends RuntimeException {
        private final DataIntegrityViolationException integrityViolation;

        private ConcurrentTemplateInsertException(DataIntegrityViolationException integrityViolation) {
            super(integrityViolation);
            this.integrityViolation = integrityViolation;
        }

        private DataIntegrityViolationException integrityViolation() {
            return integrityViolation;
        }
    }
}
