package com.moxiao.studypilot.agent.tool;

import com.moxiao.studypilot.agent.application.AgentGovernanceService;
import com.moxiao.studypilot.agent.domain.ExecutionStatus;
import com.moxiao.studypilot.assessment.api.WrongQuestionSummaryResponse;
import com.moxiao.studypilot.assessment.application.WrongQuestionService;
import com.moxiao.studypilot.learning.api.InternalLearningContextResponse;
import com.moxiao.studypilot.learning.application.InternalLearningContextService;
import com.moxiao.studypilot.notification.application.NotificationService;
import com.moxiao.studypilot.roadmap.api.ProjectWorkspaceResponse;
import com.moxiao.studypilot.roadmap.api.RoadmapMapResponse;
import com.moxiao.studypilot.roadmap.application.RoadmapArtifactService;
import com.moxiao.studypilot.roadmap.application.RoadmapQueryService;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class AgentLearningContextService {
    private final InternalLearningContextService learningContextService;
    private final RoadmapQueryService roadmapQueryService;
    private final WrongQuestionService wrongQuestionService;
    private final NotificationService notificationService;
    private final AgentGovernanceService governanceService;
    private final RoadmapArtifactService artifactService;

    public AgentLearningContextService(
            InternalLearningContextService learningContextService,
            RoadmapQueryService roadmapQueryService,
            WrongQuestionService wrongQuestionService,
            NotificationService notificationService,
            AgentGovernanceService governanceService,
            RoadmapArtifactService artifactService
    ) {
        this.learningContextService = learningContextService;
        this.roadmapQueryService = roadmapQueryService;
        this.wrongQuestionService = wrongQuestionService;
        this.notificationService = notificationService;
        this.governanceService = governanceService;
        this.artifactService = artifactService;
    }

    @Transactional(readOnly = true)
    public AgentLearningContext get(String ownerId) {
        InternalLearningContextResponse learning = learningContextService.get(ownerId);
        List<String> warnings = new ArrayList<>();
        RoadmapMapResponse roadmap = null;
        try {
            roadmap = roadmapQueryService.currentMap(ownerId);
        } catch (ResourceNotFoundException exception) {
            warnings.add(exception.getMessage());
        }
        WrongQuestionSummaryResponse wrongQuestions = wrongQuestionService.summary(ownerId);
        long unreadNotifications = notificationService.list(ownerId).stream()
                .filter(notification -> !notification.isRead()).count();
        long pendingConfirmations = governanceService.listExecutions(ownerId).stream()
                .filter(execution -> execution.getStatus() == ExecutionStatus.WAITING_CONFIRMATION
                        || execution.getStatus() == ExecutionStatus.WAITING_AUTHORIZATION)
                .count();
        List<ProjectWorkspaceResponse> workspaces = artifactService.workspaces(ownerId);
        return new AgentLearningContext(
                Instant.now(), learning, roadmap, wrongQuestions, unreadNotifications,
                pendingConfirmations, workspaces, List.copyOf(warnings));
    }

    public record AgentLearningContext(
            Instant generatedAt,
            InternalLearningContextResponse learning,
            RoadmapMapResponse roadmap,
            WrongQuestionSummaryResponse wrongQuestions,
            long unreadNotificationCount,
            long pendingConfirmationCount,
            List<ProjectWorkspaceResponse> workspaces,
            List<String> warnings
    ) {
    }
}
