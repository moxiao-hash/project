package com.moxiao.studypilot.roadmap.application;

import com.moxiao.studypilot.roadmap.api.CreateRoadmapDiagnosticRequest;
import com.moxiao.studypilot.roadmap.api.RoadmapDiagnosticResponse;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapDiagnosticEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapDiagnosticJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapEntity;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapNodeEntity;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapNodeJpaRepository;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import com.moxiao.studypilot.shared.error.ConflictException;
import com.moxiao.studypilot.assessment.infrastructure.QuestionEntity;
import com.moxiao.studypilot.assessment.infrastructure.QuizEntity;
import com.moxiao.studypilot.assessment.api.QuizAttemptResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RoadmapDiagnosticService {
    private static final int DIAGNOSTIC_QUESTION_TARGET = 10;

    private final UserRoadmapJpaRepository enrollmentRepository;
    private final RoadmapNodeJpaRepository nodeRepository;
    private final RoadmapDiagnosticJpaRepository diagnosticRepository;
    private final UserRoadmapNodeJpaRepository stateRepository;
    private final ObjectMapper objectMapper;

    public RoadmapDiagnosticService(
            UserRoadmapJpaRepository enrollmentRepository,
            RoadmapNodeJpaRepository nodeRepository,
            RoadmapDiagnosticJpaRepository diagnosticRepository,
            UserRoadmapNodeJpaRepository stateRepository,
            ObjectMapper objectMapper
    ) {
        this.enrollmentRepository = enrollmentRepository;
        this.nodeRepository = nodeRepository;
        this.diagnosticRepository = diagnosticRepository;
        this.stateRepository = stateRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RoadmapDiagnosticResponse create(
            String ownerId, CreateRoadmapDiagnosticRequest request
    ) {
        RoadmapDiagnosticEntity replay = diagnosticRepository
                .findByOwnerIdAndIdempotencyKey(ownerId, request.idempotencyKey()).orElse(null);
        if (replay != null) {
            return response(replay);
        }
        UserRoadmapEntity enrollment = current(ownerId);
        diagnosticRepository.findFirstByOwnerIdAndUserRoadmapIdOrderByCreatedAtDesc(
                        ownerId, enrollment.getId())
                .filter(item -> item.getStatus() == com.moxiao.studypilot.roadmap.domain.RoadmapDiagnosticStatus.PENDING
                        || item.getStatus() == com.moxiao.studypilot.roadmap.domain.RoadmapDiagnosticStatus.LEASED
                        || item.getStatus() == com.moxiao.studypilot.roadmap.domain.RoadmapDiagnosticStatus.READY)
                .ifPresent(item -> {
                    throw new ConflictException("当前已有未完成的路线诊断");
                });
        List<RoadmapNodeEntity> candidates = nodeRepository
                .findAllByTemplateIdInRoadmapOrder(enrollment.getTemplateId());
        if (candidates.isEmpty()) {
            throw new IllegalStateException("当前路线没有可用于诊断的节点");
        }
        List<RoadmapDiagnosticResponse.NodeSnapshot> snapshot = candidates.stream()
                .limit(DIAGNOSTIC_QUESTION_TARGET)
                .map(node -> new RoadmapDiagnosticResponse.NodeSnapshot(
                        node.getId(), node.getNodeCode(), node.getModuleId(), node.getTitle(),
                        node.getArtifactRequirementJson().contains("\"required\":true")))
                .toList();
        Instant now = Instant.now();
        RoadmapDiagnosticEntity diagnostic = diagnosticRepository.save(
                new RoadmapDiagnosticEntity(
                        UUID.randomUUID().toString(), ownerId, enrollment.getId(),
                        enrollment.getTemplateId(), request.idempotencyKey(),
                        DIAGNOSTIC_QUESTION_TARGET, snapshot.size() < DIAGNOSTIC_QUESTION_TARGET,
                        objectMapper.writeValueAsString(snapshot), now));
        return response(diagnostic);
    }

    @Transactional
    public void bindQuiz(QuizEntity quiz, List<QuestionEntity> questions, Instant now) {
        if (quiz.getPurpose() != com.moxiao.studypilot.roadmap.domain.RoadmapQuizPurpose.DIAGNOSTIC) {
            return;
        }
        RoadmapDiagnosticEntity diagnostic = diagnosticRepository
                .findFirstByOwnerIdAndUserRoadmapIdOrderByCreatedAtDesc(
                        quiz.getOwnerId(), quiz.getUserRoadmapId())
                .orElseThrow(() -> new ConflictException("请先创建路线诊断"));
        if (questions.size() != diagnostic.getQuestionTarget()) {
            throw new IllegalArgumentException("路线诊断测验题数必须与诊断快照一致");
        }
        Set<String> snapshotNodeIds = response(diagnostic).nodeSnapshot().stream()
                .map(RoadmapDiagnosticResponse.NodeSnapshot::nodeId).collect(Collectors.toSet());
        if (questions.stream().anyMatch(question -> question.getCoverageNodeId() == null
                || !snapshotNodeIds.contains(question.getCoverageNodeId()))) {
            throw new IllegalArgumentException("路线诊断题目必须绑定诊断快照中的节点");
        }
        diagnostic.bindQuiz(quiz.getId(), now);
    }

    @Transactional
    public void recordQuizResult(
            QuizEntity quiz, List<QuestionEntity> questions,
            List<QuizAttemptResponse.QuestionResult> results, Instant now
    ) {
        if (quiz.getPurpose() != com.moxiao.studypilot.roadmap.domain.RoadmapQuizPurpose.DIAGNOSTIC) {
            return;
        }
        RoadmapDiagnosticEntity diagnostic = diagnosticRepository.findByQuizId(quiz.getId())
                .orElseThrow(() -> new ResourceNotFoundException("路线诊断不存在"));
        Map<String, Boolean> correctByQuestion = results.stream().collect(Collectors.toMap(
                QuizAttemptResponse.QuestionResult::questionId,
                QuizAttemptResponse.QuestionResult::correct));
        Map<String, List<QuestionEntity>> byNode = questions.stream()
                .collect(Collectors.groupingBy(QuestionEntity::getCoverageNodeId));
        List<String> mastered = byNode.entrySet().stream()
                .filter(entry -> entry.getValue().stream()
                        .allMatch(question -> Boolean.TRUE.equals(correctByQuestion.get(question.getId()))))
                .map(Map.Entry::getKey).sorted().toList();
        UserRoadmapEntity enrollment = current(quiz.getOwnerId());
        List<String> quickVerificationNodes = new java.util.ArrayList<>();
        for (UserRoadmapNodeEntity state : stateRepository.findAllByUserRoadmapIdAndNodeIdIn(
                enrollment.getId(), mastered)) {
            try {
                state.markDiagnosticMastered(now);
                quickVerificationNodes.add(state.getNodeId());
            } catch (IllegalStateException ignored) {
                // Milestones intentionally remain on the full check-in + practice path.
            }
        }
        quickVerificationNodes.sort(String::compareTo);
        diagnostic.complete(
                quiz.getId(), objectMapper.writeValueAsString(quickVerificationNodes), now);
    }

    @Transactional(readOnly = true)
    public RoadmapDiagnosticResponse currentDiagnostic(String ownerId) {
        UserRoadmapEntity enrollment = current(ownerId);
        return diagnosticRepository
                .findFirstByOwnerIdAndUserRoadmapIdOrderByCreatedAtDesc(ownerId, enrollment.getId())
                .map(this::response)
                .orElseThrow(() -> new ResourceNotFoundException("路线诊断不存在"));
    }

    @Transactional(readOnly = true)
    public RoadmapDiagnosticResponse getById(String ownerId, String id) {
        RoadmapDiagnosticEntity entity = diagnosticRepository.findById(id)
                .filter(item -> item.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new ResourceNotFoundException("路线诊断不存在"));
        return response(entity);
    }

    private UserRoadmapEntity current(String ownerId) {
        return enrollmentRepository.findByOwnerIdAndActiveSlot(ownerId, "CURRENT")
                .orElseThrow(() -> new ResourceNotFoundException("当前学习路线不存在"));
    }

    private RoadmapDiagnosticResponse response(RoadmapDiagnosticEntity entity) {
        var type = objectMapper.getTypeFactory().constructCollectionType(
                List.class, RoadmapDiagnosticResponse.NodeSnapshot.class);
        List<RoadmapDiagnosticResponse.NodeSnapshot> snapshot =
                objectMapper.readValue(entity.getNodeSnapshotJson(), type);
        return new RoadmapDiagnosticResponse(
                entity.getId(), entity.getUserRoadmapId(), entity.getStatus().name(),
                entity.getQuestionTarget(), entity.isInsufficientQuestionFallback(), snapshot,
                objectMapper.readValue(entity.getMasteredNodeIdsJson(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)),
                entity.getQuizId(), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
