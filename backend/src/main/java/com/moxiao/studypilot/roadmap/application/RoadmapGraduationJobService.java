package com.moxiao.studypilot.roadmap.application;

import com.moxiao.studypilot.assessment.api.CreateQuizRequest;
import com.moxiao.studypilot.assessment.application.QuizService;
import com.moxiao.studypilot.roadmap.api.CompleteRoadmapQuizJobRequest;
import com.moxiao.studypilot.roadmap.api.RoadmapGraduationJobResponse;
import com.moxiao.studypilot.roadmap.domain.RoadmapQuizPurpose;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapStageGraduationEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapStageGraduationJpaRepository;
import com.moxiao.studypilot.shared.error.ConflictException;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class RoadmapGraduationJobService {
    private final RoadmapStageGraduationJpaRepository repository;
    private final RoadmapStageGraduationService graduationService;
    private final QuizService quizService;

    public RoadmapGraduationJobService(
            RoadmapStageGraduationJpaRepository repository,
            RoadmapStageGraduationService graduationService,
            QuizService quizService
    ) {
        this.repository = repository;
        this.graduationService = graduationService;
        this.quizService = quizService;
    }

    @Transactional
    public RoadmapGraduationJobResponse claim(String workerId, int leaseSeconds) {
        RoadmapStageGraduationEntity entity = repository.findClaimable(
                        Instant.now(), PageRequest.of(0, 1)).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("暂无待生成阶段毕业测验"));
        entity.claim(workerId, leaseSeconds, Instant.now());
        return response(entity);
    }

    @Transactional
    public RoadmapGraduationJobResponse complete(
            String id, CompleteRoadmapQuizJobRequest request
    ) {
        RoadmapStageGraduationEntity entity = locked(id);
        try {
            entity.requireLease(request.workerId(), request.leaseToken(), Instant.now());
        } catch (IllegalStateException exception) {
            throw new ConflictException(exception.getMessage());
        }
        if (request.quiz() == null) {
            throw new IllegalArgumentException("阶段毕业任务必须提供测验草稿");
        }
        RoadmapGraduationJobResponse job = response(entity);
        java.util.Set<String> allowedNodes = job.nodeSnapshot().stream()
                .map(com.moxiao.studypilot.roadmap.api.RoadmapDiagnosticResponse.NodeSnapshot::nodeId)
                .collect(java.util.stream.Collectors.toSet());
        java.util.Set<String> coverage = request.quiz().questions().stream()
                .map(CreateQuizRequest.QuestionInput::coverageNodeId)
                .collect(java.util.stream.Collectors.toSet());
        boolean grounded = request.quiz().questions().stream().allMatch(question ->
                Integer.valueOf(10).equals(question.points())
                        && allowedNodes.contains(question.coverageNodeId())
                        && question.sources() != null
                        && question.sources().stream().anyMatch(source ->
                        ("roadmap-node:" + question.coverageNodeId()).equals(source.locator())));
        if (!grounded || coverage.size() != 10 || !coverage.equals(allowedNodes)) {
            throw new IllegalArgumentException("阶段毕业十题必须逐题覆盖跨模块快照并带目录来源");
        }
        quizService.create(new CreateQuizRequest(
                entity.getOwnerId(), null, null, null, null,
                entity.getUserRoadmapId(), null, entity.getRoadmapStageId(),
                entity.getRoadmapTemplateId(), RoadmapQuizPurpose.STAGE_GRADUATION,
                request.quiz().title(), request.quiz().modelName(), request.quiz().questions()));
        return response(entity);
    }

    @Transactional
    public RoadmapGraduationJobResponse fail(
            String id, String workerId, String leaseToken, String error
    ) {
        RoadmapStageGraduationEntity entity = locked(id);
        try {
            entity.fail(workerId, leaseToken, error, Instant.now());
        } catch (IllegalStateException exception) {
            throw new ConflictException(exception.getMessage());
        }
        return response(entity);
    }

    private RoadmapStageGraduationEntity locked(String id) {
        return repository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("阶段毕业任务不存在"));
    }

    private RoadmapGraduationJobResponse response(RoadmapStageGraduationEntity entity) {
        return RoadmapGraduationJobResponse.from(
                entity, graduationService.getById(entity.getOwnerId(), entity.getId()));
    }
}
