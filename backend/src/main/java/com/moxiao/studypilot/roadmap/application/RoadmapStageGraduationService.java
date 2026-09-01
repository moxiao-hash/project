package com.moxiao.studypilot.roadmap.application;

import com.moxiao.studypilot.roadmap.api.CreateStageGraduationRequest;
import com.moxiao.studypilot.roadmap.api.RoadmapDiagnosticResponse;
import com.moxiao.studypilot.roadmap.api.RoadmapStageGraduationResponse;
import com.moxiao.studypilot.roadmap.domain.ArtifactStatus;
import com.moxiao.studypilot.roadmap.domain.CompletionStatus;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapStageGraduationEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapStageGraduationJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapStageJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapEntity;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapNodeEntity;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapNodeJpaRepository;
import com.moxiao.studypilot.shared.error.ConflictException;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import com.moxiao.studypilot.assessment.infrastructure.QuizEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RoadmapStageGraduationService {
    private final UserRoadmapJpaRepository enrollmentRepository;
    private final UserRoadmapNodeJpaRepository stateRepository;
    private final RoadmapStageJpaRepository stageRepository;
    private final RoadmapNodeJpaRepository nodeRepository;
    private final RoadmapStageGraduationJpaRepository graduationRepository;
    private final ObjectMapper objectMapper;

    public RoadmapStageGraduationService(
            UserRoadmapJpaRepository enrollmentRepository,
            UserRoadmapNodeJpaRepository stateRepository,
            RoadmapStageJpaRepository stageRepository,
            RoadmapNodeJpaRepository nodeRepository,
            RoadmapStageGraduationJpaRepository graduationRepository,
            ObjectMapper objectMapper
    ) {
        this.enrollmentRepository = enrollmentRepository;
        this.stateRepository = stateRepository;
        this.stageRepository = stageRepository;
        this.nodeRepository = nodeRepository;
        this.graduationRepository = graduationRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RoadmapStageGraduationResponse create(
            String ownerId, String stageId, CreateStageGraduationRequest request
    ) {
        var replay = graduationRepository
                .findByOwnerIdAndIdempotencyKey(ownerId, request.idempotencyKey()).orElse(null);
        if (replay != null) {
            return response(replay);
        }
        UserRoadmapEntity enrollment = current(ownerId);
        stageRepository.findByIdAndTemplateId(stageId, enrollment.getTemplateId())
                .orElseThrow(() -> new ResourceNotFoundException("路线阶段不存在"));
        List<RoadmapNodeEntity> nodes = nodeRepository
                .findAllByStageIdAndTemplateIdOrderByNodeOrderAsc(stageId, enrollment.getTemplateId());
        Map<String, UserRoadmapNodeEntity> states = stateRepository
                .findAllByUserRoadmapIdAndNodeIdIn(
                        enrollment.getId(), nodes.stream().map(RoadmapNodeEntity::getId).toList())
                .stream().collect(Collectors.toMap(UserRoadmapNodeEntity::getNodeId, Function.identity()));
        boolean requiredComplete = nodes.stream().filter(RoadmapNodeEntity::isRequiredNode)
                .allMatch(node -> states.containsKey(node.getId())
                        && states.get(node.getId()).getCompletionStatus() == CompletionStatus.COMPLETED);
        boolean milestoneAccepted = nodes.stream()
                .filter(node -> node.getArtifactRequirementJson().contains("\"required\":true"))
                .allMatch(node -> states.containsKey(node.getId())
                        && states.get(node.getId()).getArtifactStatus() == ArtifactStatus.ACCEPTED);
        if (!requiredComplete || !milestoneAccepted) {
            throw new ConflictException("阶段毕业要求尚未满足：必修节点和项目成果必须全部通过");
        }
        List<RoadmapDiagnosticResponse.NodeSnapshot> snapshot = nodes.stream()
                .map(node -> new RoadmapDiagnosticResponse.NodeSnapshot(
                        node.getId(), node.getNodeCode(), node.getModuleId(), node.getTitle(),
                        node.getArtifactRequirementJson().contains("\"required\":true")))
                .toList();
        Instant now = Instant.now();
        return response(graduationRepository.save(new RoadmapStageGraduationEntity(
                UUID.randomUUID().toString(), ownerId, enrollment.getId(), enrollment.getTemplateId(),
                stageId, request.idempotencyKey(), objectMapper.writeValueAsString(snapshot), now)));
    }

    @Transactional(readOnly = true)
    public RoadmapStageGraduationResponse get(String ownerId, String stageId) {
        UserRoadmapEntity enrollment = current(ownerId);
        return graduationRepository.findByOwnerIdAndUserRoadmapIdAndRoadmapStageId(
                        ownerId, enrollment.getId(), stageId)
                .map(this::response)
                .orElseThrow(() -> new ResourceNotFoundException("阶段毕业记录不存在"));
    }

    @Transactional
    public void bindQuiz(QuizEntity quiz, Instant now) {
        if (quiz.getPurpose() != com.moxiao.studypilot.roadmap.domain.RoadmapQuizPurpose.STAGE_GRADUATION) {
            return;
        }
        RoadmapStageGraduationEntity graduation = graduationRepository
                .findByOwnerIdAndUserRoadmapIdAndRoadmapStageId(
                        quiz.getOwnerId(), quiz.getUserRoadmapId(), quiz.getRoadmapStageId())
                .orElseThrow(() -> new ConflictException("请先完成阶段毕业资格校验"));
        graduation.bindQuiz(quiz.getId(), now);
    }

    @Transactional
    public void recordQuizResult(QuizEntity quiz, double score, Instant now) {
        if (quiz.getPurpose() != com.moxiao.studypilot.roadmap.domain.RoadmapQuizPurpose.STAGE_GRADUATION) {
            return;
        }
        RoadmapStageGraduationEntity graduation = graduationRepository
                .findByOwnerIdAndUserRoadmapIdAndRoadmapStageId(
                        quiz.getOwnerId(), quiz.getUserRoadmapId(), quiz.getRoadmapStageId())
                .orElseThrow(() -> new ResourceNotFoundException("阶段毕业记录不存在"));
        graduation.complete(score, quiz.getId(), now);
    }

    private UserRoadmapEntity current(String ownerId) {
        return enrollmentRepository.findByOwnerIdAndActiveSlot(ownerId, "CURRENT")
                .orElseThrow(() -> new ResourceNotFoundException("当前学习路线不存在"));
    }

    private RoadmapStageGraduationResponse response(RoadmapStageGraduationEntity entity) {
        var type = objectMapper.getTypeFactory().constructCollectionType(
                List.class, RoadmapDiagnosticResponse.NodeSnapshot.class);
        List<RoadmapDiagnosticResponse.NodeSnapshot> snapshot =
                objectMapper.readValue(entity.getNodeSnapshotJson(), type);
        return new RoadmapStageGraduationResponse(
                entity.getId(), entity.getUserRoadmapId(), entity.getRoadmapStageId(),
                entity.getStatus(), entity.getQuestionTarget(), snapshot, entity.getQuizId(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
