package com.moxiao.studypilot.roadmap.application;

import com.moxiao.studypilot.assessment.infrastructure.QuizEntity;
import com.moxiao.studypilot.assessment.infrastructure.QuizJpaRepository;
import com.moxiao.studypilot.roadmap.api.CreateRoadmapNodeCheckInRequest;
import com.moxiao.studypilot.roadmap.api.RoadmapNodeCheckInResponse;
import com.moxiao.studypilot.roadmap.api.RetryRoadmapQuizRequest;
import com.moxiao.studypilot.roadmap.api.RoadmapNodeQuizResponse;
import com.moxiao.studypilot.roadmap.api.RoadmapQuizGenerationResponse;
import com.moxiao.studypilot.roadmap.api.RoadmapQuizJobPayload;
import com.moxiao.studypilot.roadmap.domain.AvailabilityStatus;
import com.moxiao.studypilot.roadmap.domain.RoadmapQuizGenerationStatus;
import com.moxiao.studypilot.roadmap.domain.RoadmapQuizPurpose;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeCheckInEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeCheckInJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapQuizGenerationJobEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapQuizGenerationJobJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapEntity;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapNodeEntity;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapNodeJpaRepository;
import com.moxiao.studypilot.shared.error.ConflictException;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class RoadmapLearningLoopService {
    private static final String CURRENT = "CURRENT";

    private final UserRoadmapJpaRepository enrollmentRepository;
    private final UserRoadmapNodeJpaRepository stateRepository;
    private final RoadmapNodeCheckInJpaRepository checkInRepository;
    private final RoadmapQuizGenerationJobJpaRepository jobRepository;
    private final QuizJpaRepository quizRepository;

    public RoadmapLearningLoopService(
            UserRoadmapJpaRepository enrollmentRepository,
            UserRoadmapNodeJpaRepository stateRepository,
            RoadmapNodeCheckInJpaRepository checkInRepository,
            RoadmapQuizGenerationJobJpaRepository jobRepository,
            QuizJpaRepository quizRepository
    ) {
        this.enrollmentRepository = enrollmentRepository;
        this.stateRepository = stateRepository;
        this.checkInRepository = checkInRepository;
        this.jobRepository = jobRepository;
        this.quizRepository = quizRepository;
    }

    @Transactional
    public RoadmapNodeCheckInResponse checkIn(
            String ownerId,
            String nodeId,
            CreateRoadmapNodeCheckInRequest request
    ) {
        UserRoadmapEntity enrollment = currentEnrollmentForUpdate(ownerId);
        UserRoadmapNodeEntity state = currentNodeForUpdate(enrollment.getId(), nodeId);
        RoadmapNodeCheckInEntity existing = checkInRepository
                .findByUserRoadmapNodeId(state.getId()).orElse(null);
        if (existing != null) {
            if (!existing.getIdempotencyKey().equals(request.idempotencyKey())
                    || !existing.getSummary().equals(request.summary().trim())) {
                throw new ConflictException("该路线节点已经提交过不同的打卡内容");
            }
            return response(existing);
        }
        checkInRepository.findByOwnerIdAndIdempotencyKey(ownerId, request.idempotencyKey())
                .ifPresent(ignored -> {
                    throw new ConflictException("打卡幂等键已用于其他请求");
                });
        if (state.getAvailabilityStatus() != AvailabilityStatus.AVAILABLE) {
            throw new ConflictException("完成前置节点后才能提交打卡");
        }

        Instant now = Instant.now();
        RoadmapNodeCheckInEntity checkIn = checkInRepository.save(new RoadmapNodeCheckInEntity(
                UUID.randomUUID().toString(), ownerId, enrollment.getId(), state.getId(), nodeId,
                request.summary().trim(), request.idempotencyKey(), now));
        RoadmapQuizGenerationJobEntity job = jobRepository.save(
                new RoadmapQuizGenerationJobEntity(
                        UUID.randomUUID().toString(), ownerId, enrollment.getId(), state.getId(),
                        nodeId, checkIn.getId(), RoadmapQuizPurpose.NODE, 0, now));
        state.submitCheckInAndQueueQuiz(now);
        return RoadmapNodeCheckInResponse.from(checkIn, job);
    }

    @Transactional(readOnly = true)
    public List<RoadmapNodeCheckInResponse> checkIns(String ownerId, String nodeId) {
        UserRoadmapEntity enrollment = enrollmentRepository
                .findByOwnerIdAndActiveSlot(ownerId, CURRENT)
                .orElseThrow(() -> new ResourceNotFoundException("当前学习路线不存在"));
        UserRoadmapNodeEntity state = stateRepository
                .findByUserRoadmapIdAndNodeId(enrollment.getId(), nodeId)
                .orElseThrow(() -> new ResourceNotFoundException("路线节点不存在"));
        return checkInRepository
                .findAllByOwnerIdAndUserRoadmapNodeIdOrderByCreatedAtDesc(ownerId, state.getId())
                .stream().map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public RoadmapNodeQuizResponse quiz(String ownerId, String nodeId) {
        UserRoadmapEntity enrollment = enrollmentRepository
                .findByOwnerIdAndActiveSlot(ownerId, CURRENT)
                .orElseThrow(() -> new ResourceNotFoundException("当前学习路线不存在"));
        UserRoadmapNodeEntity state = stateRepository
                .findByUserRoadmapIdAndNodeId(enrollment.getId(), nodeId)
                .orElseThrow(() -> new ResourceNotFoundException("路线节点不存在"));
        RoadmapQuizGenerationJobEntity job = jobRepository
                .findFirstByUserRoadmapNodeIdOrderByCreatedAtDesc(state.getId())
                .orElseThrow(() -> new ResourceNotFoundException("节点测验尚未生成"));
        return new RoadmapNodeQuizResponse(nodeId, state.getQuizStatus().name(),
                job.getQuizId(), RoadmapQuizGenerationResponse.from(job));
    }

    @Transactional
    public RoadmapQuizGenerationResponse retryQuiz(
            String ownerId, String nodeId, RetryRoadmapQuizRequest request
    ) {
        UserRoadmapEntity enrollment = currentEnrollmentForUpdate(ownerId);
        UserRoadmapNodeEntity state = currentNodeForUpdate(enrollment.getId(), nodeId);
        RoadmapQuizGenerationJobEntity replay = jobRepository
                .findByOwnerIdAndUserRoadmapNodeIdAndRetryIdempotencyKey(
                        ownerId, state.getId(), request.idempotencyKey()).orElse(null);
        if (replay != null) {
            return RoadmapQuizGenerationResponse.from(replay);
        }
        RoadmapQuizGenerationJobEntity latest = jobRepository
                .findFirstByUserRoadmapNodeIdOrderByCreatedAtDesc(state.getId())
                .orElseThrow(() -> new ResourceNotFoundException("节点测验生成任务不存在"));
        if (latest.getStatus() != RoadmapQuizGenerationStatus.FAILED) {
            throw new ConflictException("仅失败的节点测验可以重试");
        }
        if (latest.getRetrySequence() >= 3) {
            throw new ConflictException("节点测验重试次数已达上限");
        }
        Instant now = Instant.now();
        RoadmapQuizGenerationJobEntity retry = jobRepository.save(new RoadmapQuizGenerationJobEntity(
                UUID.randomUUID().toString(), ownerId, enrollment.getId(), state.getId(), nodeId,
                latest.getCheckInId(), RoadmapQuizPurpose.NODE, latest.getRetrySequence() + 1,
                request.idempotencyKey(), now));
        state.retryQuizGeneration(now);
        return RoadmapQuizGenerationResponse.from(retry);
    }

    @Transactional
    public RoadmapQuizJobPayload claimQuizJob(String workerId, int leaseSeconds) {
        Instant now = Instant.now();
        RoadmapQuizGenerationJobEntity job = jobRepository.findClaimable(now, PageRequest.of(0, 1))
                .stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("暂无待生成路线测验"));
        job.claim(workerId, leaseSeconds, now);
        return payload(job);
    }

    @Transactional
    public RoadmapQuizJobPayload heartbeatQuizJob(
            String jobId, String workerId, int leaseSeconds
    ) {
        RoadmapQuizGenerationJobEntity job = lockedJob(jobId);
        try {
            job.heartbeat(workerId, leaseSeconds, Instant.now());
        } catch (IllegalArgumentException exception) {
            throw new ConflictException(exception.getMessage());
        }
        return payload(job);
    }

    @Transactional
    public RoadmapQuizJobPayload failQuizJob(String jobId, String workerId, String error) {
        RoadmapQuizGenerationJobEntity job = lockedJob(jobId);
        Instant now = Instant.now();
        try {
            job.fail(workerId, error, now);
        } catch (IllegalArgumentException exception) {
            throw new ConflictException(exception.getMessage());
        }
        if (job.getStatus() == RoadmapQuizGenerationStatus.FAILED) {
            stateRepository.findById(job.getUserRoadmapNodeId())
                    .orElseThrow(() -> new ResourceNotFoundException("路线节点状态不存在"))
                    .markQuizGenerationFailed(now);
        }
        return payload(job);
    }

    @Transactional
    public RoadmapQuizJobPayload completeQuizJob(
            String jobId, String workerId, String quizId
    ) {
        RoadmapQuizGenerationJobEntity job = lockedJob(jobId);
        QuizEntity quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("节点测验不存在"));
        if (!job.getOwnerId().equals(quiz.getOwnerId())
                || !job.getNodeId().equals(quiz.getRoadmapNodeId())
                || quiz.getPurpose() != RoadmapQuizPurpose.NODE) {
            throw new ConflictException("测验与路线生成任务不匹配");
        }
        Instant now = Instant.now();
        try {
            job.complete(workerId, quizId, now);
        } catch (IllegalArgumentException exception) {
            throw new ConflictException(exception.getMessage());
        }
        stateRepository.findById(job.getUserRoadmapNodeId())
                .orElseThrow(() -> new ResourceNotFoundException("路线节点状态不存在"))
                .markQuizReady(now);
        return payload(job);
    }

    private RoadmapQuizGenerationJobEntity lockedJob(String jobId) {
        return jobRepository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("路线测验生成任务不存在"));
    }

    private RoadmapQuizJobPayload payload(RoadmapQuizGenerationJobEntity job) {
        RoadmapNodeCheckInEntity checkIn = checkInRepository.findById(job.getCheckInId())
                .orElseThrow(() -> new ResourceNotFoundException("路线节点打卡不存在"));
        return RoadmapQuizJobPayload.from(job, checkIn);
    }

    private RoadmapNodeCheckInResponse response(RoadmapNodeCheckInEntity checkIn) {
        RoadmapQuizGenerationJobEntity job = jobRepository
                .findAllByCheckInIdOrderByRetrySequenceDesc(checkIn.getId()).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("路线打卡缺少测验生成任务"));
        return RoadmapNodeCheckInResponse.from(checkIn, job);
    }

    private UserRoadmapEntity currentEnrollmentForUpdate(String ownerId) {
        return enrollmentRepository.findByOwnerIdAndActiveSlotForUpdate(ownerId, CURRENT)
                .orElseThrow(() -> new ResourceNotFoundException("当前学习路线不存在"));
    }

    private UserRoadmapNodeEntity currentNodeForUpdate(String enrollmentId, String nodeId) {
        return stateRepository.findByUserRoadmapIdAndNodeIdForUpdate(enrollmentId, nodeId)
                .orElseThrow(() -> new ResourceNotFoundException("路线节点不存在"));
    }
}
