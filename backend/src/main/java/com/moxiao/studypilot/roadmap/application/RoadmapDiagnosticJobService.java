package com.moxiao.studypilot.roadmap.application;

import com.moxiao.studypilot.assessment.api.CreateQuizRequest;
import com.moxiao.studypilot.assessment.application.QuizService;
import com.moxiao.studypilot.roadmap.api.CompleteRoadmapQuizJobRequest;
import com.moxiao.studypilot.roadmap.api.RoadmapDiagnosticJobResponse;
import com.moxiao.studypilot.roadmap.domain.RoadmapQuizPurpose;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapDiagnosticEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapDiagnosticJpaRepository;
import com.moxiao.studypilot.shared.error.ConflictException;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class RoadmapDiagnosticJobService {
    private final RoadmapDiagnosticJpaRepository repository;
    private final RoadmapDiagnosticService diagnosticService;
    private final QuizService quizService;

    public RoadmapDiagnosticJobService(
            RoadmapDiagnosticJpaRepository repository,
            RoadmapDiagnosticService diagnosticService,
            QuizService quizService
    ) {
        this.repository = repository;
        this.diagnosticService = diagnosticService;
        this.quizService = quizService;
    }

    @Transactional
    public RoadmapDiagnosticJobResponse claim(String workerId, int leaseSeconds) {
        RoadmapDiagnosticEntity entity = repository.findClaimable(
                        Instant.now(), PageRequest.of(0, 1)).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("暂无待生成路线诊断"));
        entity.claim(workerId, leaseSeconds, Instant.now());
        return response(entity);
    }

    @Transactional
    public RoadmapDiagnosticJobResponse heartbeat(
            String id, String workerId, String leaseToken, int leaseSeconds
    ) {
        RoadmapDiagnosticEntity entity = locked(id);
        try {
            entity.heartbeat(workerId, leaseToken, leaseSeconds, Instant.now());
        } catch (IllegalStateException exception) {
            throw new ConflictException(exception.getMessage());
        }
        return response(entity);
    }

    @Transactional
    public RoadmapDiagnosticJobResponse complete(
            String id, CompleteRoadmapQuizJobRequest request
    ) {
        RoadmapDiagnosticEntity entity = locked(id);
        try {
            entity.requireLease(request.workerId(), request.leaseToken(), Instant.now());
        } catch (IllegalStateException exception) {
            throw new ConflictException(exception.getMessage());
        }
        if (request.quiz() == null) {
            throw new IllegalArgumentException("路线诊断必须提供测验草稿");
        }
        quizService.create(new CreateQuizRequest(
                entity.getOwnerId(), null, null, null, null,
                entity.getUserRoadmapId(), null, null, null,
                RoadmapQuizPurpose.DIAGNOSTIC, request.quiz().title(),
                request.quiz().modelName(), request.quiz().questions()));
        return response(entity);
    }

    @Transactional
    public RoadmapDiagnosticJobResponse fail(
            String id, String workerId, String leaseToken, String error
    ) {
        RoadmapDiagnosticEntity entity = locked(id);
        try {
            entity.fail(workerId, leaseToken, error, Instant.now());
        } catch (IllegalStateException exception) {
            throw new ConflictException(exception.getMessage());
        }
        return response(entity);
    }

    private RoadmapDiagnosticEntity locked(String id) {
        return repository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("路线诊断任务不存在"));
    }

    private RoadmapDiagnosticJobResponse response(RoadmapDiagnosticEntity entity) {
        return RoadmapDiagnosticJobResponse.from(
                entity, diagnosticService.getById(entity.getOwnerId(), entity.getId()));
    }
}
