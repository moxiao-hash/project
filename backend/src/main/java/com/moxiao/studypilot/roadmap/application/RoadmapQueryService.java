package com.moxiao.studypilot.roadmap.application;

import com.moxiao.studypilot.roadmap.api.RoadmapEnrollmentResponse;
import com.moxiao.studypilot.roadmap.api.RoadmapMapResponse;
import com.moxiao.studypilot.roadmap.api.RoadmapNodeResponse;
import com.moxiao.studypilot.roadmap.api.RoadmapStageResponse;
import com.moxiao.studypilot.roadmap.domain.AvailabilityStatus;
import com.moxiao.studypilot.roadmap.domain.CheckInStatus;
import com.moxiao.studypilot.roadmap.domain.CompletionStatus;
import com.moxiao.studypilot.roadmap.domain.LearningStatus;
import com.moxiao.studypilot.roadmap.domain.QuizStatus;
import com.moxiao.studypilot.roadmap.domain.RoadmapDisplayStatus;
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
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class RoadmapQueryService {

    private static final String CURRENT = "CURRENT";

    private final UserRoadmapJpaRepository userRoadmapRepository;
    private final RoadmapTemplateJpaRepository templateRepository;
    private final RoadmapStageJpaRepository stageRepository;
    private final RoadmapNodeJpaRepository nodeRepository;
    private final RoadmapNodePrerequisiteJpaRepository prerequisiteRepository;
    private final UserRoadmapNodeJpaRepository userNodeRepository;
    private final ObjectMapper objectMapper;

    public RoadmapQueryService(
            UserRoadmapJpaRepository userRoadmapRepository,
            RoadmapTemplateJpaRepository templateRepository,
            RoadmapStageJpaRepository stageRepository,
            RoadmapNodeJpaRepository nodeRepository,
            RoadmapNodePrerequisiteJpaRepository prerequisiteRepository,
            UserRoadmapNodeJpaRepository userNodeRepository,
            ObjectMapper objectMapper
    ) {
        this.userRoadmapRepository = userRoadmapRepository;
        this.templateRepository = templateRepository;
        this.stageRepository = stageRepository;
        this.nodeRepository = nodeRepository;
        this.prerequisiteRepository = prerequisiteRepository;
        this.userNodeRepository = userNodeRepository;
        this.objectMapper = objectMapper;
    }

    public RoadmapEnrollmentResponse current(String ownerId) {
        UserRoadmapEntity enrollment = currentEnrollment(ownerId);
        RoadmapTemplateEntity template = template(enrollment.getTemplateId());
        return RoadmapEnrollmentResponse.from(enrollment, template);
    }

    public RoadmapMapResponse currentMap(String ownerId) {
        return loadCurrentMap(ownerId);
    }

    public RoadmapStageResponse currentStage(String ownerId, String stageId) {
        UserRoadmapEntity enrollment = currentEnrollment(ownerId);
        RoadmapStageEntity stage = stageRepository
                .findByIdAndTemplateId(stageId, enrollment.getTemplateId())
                .orElseThrow(() -> new ResourceNotFoundException("路线阶段不存在"));
        List<RoadmapNodeEntity> nodes = nodeRepository
                .findAllByStageIdAndTemplateIdOrderByNodeOrderAsc(
                        stageId, enrollment.getTemplateId());
        List<String> nodeIds = nodes.stream().map(RoadmapNodeEntity::getId).toList();
        Map<String, UserRoadmapNodeEntity> stateByNodeId = uniqueIndex(
                userNodeRepository.findAllByUserRoadmapIdAndNodeIdIn(enrollment.getId(), nodeIds),
                UserRoadmapNodeEntity::getNodeId,
                "用户路线节点状态重复");
        List<RoadmapNodePrerequisiteEntity> edges = prerequisiteRepository
                .findAllByTemplateIdAndNodeIdIn(enrollment.getTemplateId(), nodeIds);
        List<RoadmapNodeEntity> orderedPrerequisites = orderedPrerequisiteNodes(
                enrollment.getTemplateId(), edges);
        Map<String, RoadmapNodeEntity> prerequisitesById = uniqueIndex(
                orderedPrerequisites, RoadmapNodeEntity::getId, "路线前置节点重复");
        return toStageResponse(
                stage,
                nodes,
                stateByNodeId,
                prerequisiteCodes(edges, prerequisitesById, orderedPrerequisites));
    }

    public RoadmapNodeResponse currentNode(String ownerId, String nodeId) {
        UserRoadmapEntity enrollment = currentEnrollment(ownerId);
        RoadmapNodeEntity node = nodeRepository
                .findByIdAndTemplateId(nodeId, enrollment.getTemplateId())
                .orElseThrow(() -> new ResourceNotFoundException("路线节点不存在"));
        UserRoadmapNodeEntity state = userNodeRepository
                .findByUserRoadmapIdAndNodeId(enrollment.getId(), nodeId)
                .orElseThrow(() -> new IllegalStateException(
                        "用户路线节点状态不存在: " + nodeId));
        List<RoadmapNodePrerequisiteEntity> edges = prerequisiteRepository
                .findAllByTemplateIdAndNodeId(enrollment.getTemplateId(), nodeId);
        List<RoadmapNodeEntity> orderedPrerequisites = orderedPrerequisiteNodes(
                enrollment.getTemplateId(), edges);
        Map<String, RoadmapNodeEntity> prerequisitesById = uniqueIndex(
                orderedPrerequisites, RoadmapNodeEntity::getId, "路线前置节点重复");
        return toNodeResponse(
                node,
                state,
                prerequisiteCodes(edges, prerequisitesById, orderedPrerequisites)
                        .getOrDefault(nodeId, List.of()));
    }

    private RoadmapMapResponse loadCurrentMap(String ownerId) {
        UserRoadmapEntity enrollment = currentEnrollment(ownerId);
        RoadmapTemplateEntity template = template(enrollment.getTemplateId());
        List<RoadmapStageEntity> stages = stageRepository
                .findAllByTemplateIdOrderByStageOrderAsc(template.getId());
        List<RoadmapNodeEntity> nodes = orderedNodes(template.getId(), stages);
        List<RoadmapNodePrerequisiteEntity> prerequisites = prerequisiteRepository
                .findAllByTemplateId(template.getId());
        List<UserRoadmapNodeEntity> userStates = userNodeRepository
                .findAllByUserRoadmapId(enrollment.getId());

        Map<String, UserRoadmapNodeEntity> stateByNodeId = uniqueIndex(
                userStates, UserRoadmapNodeEntity::getNodeId, "用户路线节点状态重复");
        Map<String, RoadmapNodeEntity> nodeById = uniqueIndex(
                nodes, RoadmapNodeEntity::getId, "路线节点重复");
        Map<String, List<String>> prerequisiteCodes = prerequisiteCodes(
                prerequisites, nodeById, nodes);
        Map<String, List<RoadmapNodeEntity>> nodesByStageId = nodes.stream()
                .collect(Collectors.groupingBy(
                        RoadmapNodeEntity::getStageId,
                        java.util.LinkedHashMap::new,
                        Collectors.toList()));

        List<RoadmapStageResponse> stageResponses = stages.stream()
                .map(stage -> toStageResponse(
                        stage,
                        nodesByStageId.getOrDefault(stage.getId(), List.of()),
                        stateByNodeId,
                        prerequisiteCodes))
                .toList();
        int totalRequired = stageResponses.stream()
                .mapToInt(RoadmapStageResponse::totalRequiredNodes).sum();
        int completedRequired = stageResponses.stream()
                .mapToInt(RoadmapStageResponse::completedRequiredNodes).sum();
        return new RoadmapMapResponse(
                enrollment.getId(),
                template.getRoadmapCode(),
                template.getTemplateVersion(),
                template.getTitle(),
                template.getDescription(),
                completedRequired,
                totalRequired,
                stageResponses);
    }

    private List<RoadmapNodeEntity> orderedNodes(
            String templateId,
            List<RoadmapStageEntity> stages
    ) {
        Map<String, Integer> stageOrder = stages.stream().collect(Collectors.toMap(
                RoadmapStageEntity::getId,
                RoadmapStageEntity::getStageOrder));
        List<RoadmapNodeEntity> nodes = new ArrayList<>(nodeRepository
                .findAllByTemplateIdOrderByStageIdAscNodeOrderAsc(templateId));
        nodes.sort(Comparator
                .comparingInt((RoadmapNodeEntity node) -> stageOrder.getOrDefault(
                        node.getStageId(), Integer.MAX_VALUE))
                .thenComparingInt(RoadmapNodeEntity::getNodeOrder));
        return nodes;
    }

    private Map<String, List<String>> prerequisiteCodes(
            List<RoadmapNodePrerequisiteEntity> prerequisites,
            Map<String, RoadmapNodeEntity> nodeById,
            List<RoadmapNodeEntity> orderedNodes
    ) {
        Map<String, Integer> nodeOrder = new HashMap<>();
        for (int index = 0; index < orderedNodes.size(); index++) {
            nodeOrder.put(orderedNodes.get(index).getId(), index);
        }
        Map<String, List<RoadmapNodePrerequisiteEntity>> byNode = prerequisites.stream()
                .collect(Collectors.groupingBy(RoadmapNodePrerequisiteEntity::getNodeId));
        Map<String, List<String>> result = new HashMap<>();
        byNode.forEach((nodeId, edges) -> result.put(nodeId, edges.stream()
                .sorted(Comparator.comparingInt(edge -> nodeOrder.getOrDefault(
                        edge.getPrerequisiteNodeId(), Integer.MAX_VALUE)))
                .map(edge -> requiredNode(nodeById, edge.getPrerequisiteNodeId()).getNodeCode())
                .toList()));
        return result;
    }

    private List<RoadmapNodeEntity> orderedPrerequisiteNodes(
            String templateId,
            List<RoadmapNodePrerequisiteEntity> edges
    ) {
        List<String> prerequisiteIds = edges.stream()
                .map(RoadmapNodePrerequisiteEntity::getPrerequisiteNodeId)
                .distinct()
                .toList();
        if (prerequisiteIds.isEmpty()) {
            return List.of();
        }
        return nodeRepository.findAllByTemplateIdAndIdInRoadmapOrder(
                templateId, prerequisiteIds);
    }

    private RoadmapStageResponse toStageResponse(
            RoadmapStageEntity stage,
            List<RoadmapNodeEntity> nodes,
            Map<String, UserRoadmapNodeEntity> stateByNodeId,
            Map<String, List<String>> prerequisiteCodes
    ) {
        List<RoadmapNodeResponse> nodeResponses = nodes.stream()
                .map(node -> toNodeResponse(
                        node,
                        requiredState(stateByNodeId, node.getId()),
                        prerequisiteCodes.getOrDefault(node.getId(), List.of())))
                .toList();
        int totalRequired = (int) nodes.stream().filter(RoadmapNodeEntity::isRequiredNode).count();
        int completedRequired = (int) nodes.stream()
                .filter(RoadmapNodeEntity::isRequiredNode)
                .filter(node -> requiredState(stateByNodeId, node.getId()).getCompletionStatus()
                        == CompletionStatus.COMPLETED)
                .count();
        return new RoadmapStageResponse(
                stage.getId(),
                stage.getStageCode(),
                stage.getStageOrder(),
                stage.getTitle(),
                stage.getDescription(),
                stage.getGraduationProjectTitle(),
                completedRequired,
                totalRequired,
                nodeResponses);
    }

    private RoadmapNodeResponse toNodeResponse(
            RoadmapNodeEntity node,
            UserRoadmapNodeEntity state,
            List<String> prerequisiteCodes
    ) {
        return new RoadmapNodeResponse(
                node.getId(),
                node.getNodeCode(),
                node.getNodeOrder(),
                node.getTitle(),
                stringList(node.getObjectivesJson(), node.getId(), "objectives"),
                stringList(node.getHighFrequencyJson(), node.getId(), "highFrequency"),
                stringList(node.getCommonMistakesJson(), node.getId(), "commonMistakes"),
                stringList(node.getSearchKeywordsJson(), node.getId(), "searchKeywords"),
                node.getEstimatedMinutes(),
                node.getPracticeMinutes(),
                node.getDifficulty(),
                node.isRequiredNode(),
                prerequisiteCodes,
                state.getAvailabilityStatus().name(),
                state.getLearningStatus().name(),
                state.getCheckInStatus().name(),
                state.getQuizStatus().name(),
                state.getArtifactStatus().name(),
                state.getCompletionStatus().name(),
                displayStatus(state).name(),
                state.getRowVersion());
    }

    private List<String> stringList(String json, String nodeId, String field) {
        try {
            JsonNode value = objectMapper.readTree(json);
            if (value == null || !value.isArray()) {
                throw invalidNodeMetadata(nodeId, field);
            }
            List<String> result = new ArrayList<>();
            for (JsonNode item : value) {
                if (!item.isTextual()) {
                    throw invalidNodeMetadata(nodeId, field);
                }
                result.add(item.asText());
            }
            return List.copyOf(result);
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalStateException) {
                throw exception;
            }
            throw invalidNodeMetadata(nodeId, field, exception);
        }
    }

    private RoadmapDisplayStatus displayStatus(UserRoadmapNodeEntity state) {
        if (state.getCompletionStatus() == CompletionStatus.COMPLETED) {
            return RoadmapDisplayStatus.COMPLETED;
        }
        if (state.getAvailabilityStatus() == AvailabilityStatus.LOCKED) {
            return RoadmapDisplayStatus.LOCKED;
        }
        if (state.getQuizStatus() == QuizStatus.FAILED
                || state.getQuizStatus() == QuizStatus.PARTIALLY_GRADED) {
            return RoadmapDisplayStatus.REVIEW_REQUIRED;
        }
        if (state.getCheckInStatus() == CheckInStatus.SUBMITTED
                && (state.getQuizStatus() == QuizStatus.NOT_GENERATED
                || state.getQuizStatus() == QuizStatus.GENERATING
                || state.getQuizStatus() == QuizStatus.EVALUATING)) {
            return RoadmapDisplayStatus.QUIZ_PENDING;
        }
        return switch (state.getLearningStatus()) {
            case NOT_STARTED -> RoadmapDisplayStatus.AVAILABLE;
            case SCHEDULED -> RoadmapDisplayStatus.SCHEDULED;
            case IN_PROGRESS -> RoadmapDisplayStatus.IN_PROGRESS;
        };
    }

    private UserRoadmapEntity currentEnrollment(String ownerId) {
        return userRoadmapRepository.findByOwnerIdAndActiveSlot(ownerId, CURRENT)
                .orElseThrow(() -> new ResourceNotFoundException("当前路线不存在"));
    }

    private RoadmapTemplateEntity template(String templateId) {
        return templateRepository.findById(templateId)
                .orElseThrow(() -> new IllegalStateException("当前路线模板不存在: " + templateId));
    }

    private static UserRoadmapNodeEntity requiredState(
            Map<String, UserRoadmapNodeEntity> states,
            String nodeId
    ) {
        UserRoadmapNodeEntity state = states.get(nodeId);
        if (state == null) {
            throw new IllegalStateException("用户路线节点状态不存在: " + nodeId);
        }
        return state;
    }

    private static RoadmapNodeEntity requiredNode(
            Map<String, RoadmapNodeEntity> nodes,
            String nodeId
    ) {
        RoadmapNodeEntity node = nodes.get(nodeId);
        if (node == null) {
            throw new IllegalStateException("路线前置节点不存在: " + nodeId);
        }
        return node;
    }

    private static <T> Map<String, T> uniqueIndex(
            List<T> values,
            Function<T, String> key,
            String duplicateMessage
    ) {
        try {
            return values.stream().collect(Collectors.toMap(key, Function.identity()));
        } catch (IllegalStateException exception) {
            throw new IllegalStateException(duplicateMessage, exception);
        }
    }

    private static IllegalStateException invalidNodeMetadata(String nodeId, String field) {
        return new IllegalStateException("路线节点元数据无效: " + nodeId + "/" + field);
    }

    private static IllegalStateException invalidNodeMetadata(
            String nodeId,
            String field,
            RuntimeException cause
    ) {
        return new IllegalStateException("路线节点元数据无效: " + nodeId + "/" + field, cause);
    }
}
