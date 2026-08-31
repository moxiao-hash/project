package com.moxiao.studypilot.roadmap.application;

import com.moxiao.studypilot.assessment.infrastructure.QuizEntity;
import com.moxiao.studypilot.roadmap.domain.RoadmapQuizPurpose;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapEntity;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapNodeEntity;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapNodeJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeMutationService;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class RoadmapQuizProgressService {
    private static final String CURRENT = "CURRENT";

    private final UserRoadmapJpaRepository enrollmentRepository;
    private final UserRoadmapNodeJpaRepository stateRepository;
    private final RoadmapNodeMutationService mutationService;

    public RoadmapQuizProgressService(
            UserRoadmapJpaRepository enrollmentRepository,
            UserRoadmapNodeJpaRepository stateRepository,
            RoadmapNodeMutationService mutationService
    ) {
        this.enrollmentRepository = enrollmentRepository;
        this.stateRepository = stateRepository;
        this.mutationService = mutationService;
    }

    @Transactional
    public void markEvaluating(QuizEntity quiz, Instant now) {
        stateForUpdate(quiz).markQuizEvaluating(now);
    }

    @Transactional
    public void recordResult(QuizEntity quiz, double score, Instant now) {
        mutationService.recordNodeQuizResult(quiz, score, now);
    }

    private UserRoadmapNodeEntity stateForUpdate(QuizEntity quiz) {
        if (quiz.getPurpose() != RoadmapQuizPurpose.NODE) {
            throw new IllegalArgumentException("仅节点测验可以更新路线测验状态");
        }
        UserRoadmapEntity enrollment = enrollmentRepository
                .findByOwnerIdAndActiveSlotForUpdate(quiz.getOwnerId(), CURRENT)
                .orElseThrow(() -> new ResourceNotFoundException("当前学习路线不存在"));
        return stateRepository.findByUserRoadmapIdAndNodeIdForUpdate(
                        enrollment.getId(), quiz.getRoadmapNodeId())
                .orElseThrow(() -> new ResourceNotFoundException("路线节点状态不存在"));
    }
}
