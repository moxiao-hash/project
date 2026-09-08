package com.moxiao.studypilot.agent.tool;

import com.moxiao.studypilot.agent.application.AgentGovernanceService;
import com.moxiao.studypilot.agent.domain.AgentScope;
import com.moxiao.studypilot.agent.domain.ExecutionStatus;
import com.moxiao.studypilot.agent.domain.ExecutionType;
import com.moxiao.studypilot.agent.domain.RiskLevel;
import com.moxiao.studypilot.agent.domain.TriggerType;
import com.moxiao.studypilot.agent.infrastructure.AgentExecutionEntity;
import com.moxiao.studypilot.assessment.api.WrongQuestionSummaryResponse;
import com.moxiao.studypilot.assessment.application.WrongQuestionService;
import com.moxiao.studypilot.learning.api.InternalLearningContextResponse;
import com.moxiao.studypilot.learning.application.InternalLearningContextService;
import com.moxiao.studypilot.notification.application.NotificationService;
import com.moxiao.studypilot.notification.domain.NotificationType;
import com.moxiao.studypilot.notification.infrastructure.NotificationEntity;
import com.moxiao.studypilot.roadmap.api.RoadmapMapResponse;
import com.moxiao.studypilot.roadmap.application.RoadmapArtifactService;
import com.moxiao.studypilot.roadmap.application.RoadmapQueryService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentLearningContextServiceTest {

    private final InternalLearningContextService learning = mock(InternalLearningContextService.class);
    private final RoadmapQueryService roadmap = mock(RoadmapQueryService.class);
    private final WrongQuestionService wrongQuestions = mock(WrongQuestionService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final AgentGovernanceService governance = mock(AgentGovernanceService.class);
    private final RoadmapArtifactService artifacts = mock(RoadmapArtifactService.class);
    private final AgentLearningContextService service = new AgentLearningContextService(
            learning, roadmap, wrongQuestions, notifications, governance, artifacts);

    @Test
    void aggregatesFreshOwnerScopedLearningSignals() {
        InternalLearningContextResponse base = new InternalLearningContextResponse(
                "Asia/Shanghai", List.of(), List.of(), List.of(), List.of(), List.of());
        RoadmapMapResponse map = new RoadmapMapResponse(
                "enrollment-1", "java-ai", 2, "路线", "desc", 2, 125, List.of());
        WrongQuestionSummaryResponse wrong = new WrongQuestionSummaryResponse(
                3, 4, List.of(), null);
        NotificationEntity unread = new NotificationEntity(
                "notification-1", "user-1", NotificationType.AGENT_FAILED,
                "待处理", "content", Instant.now());
        AgentExecutionEntity pending = new AgentExecutionEntity(
                "execution-1", "user-1", "key-1", ExecutionType.PLAN_ADJUSTMENT,
                TriggerType.USER_REQUEST, RiskLevel.HIGH, AgentScope.LARGE_PLAN_ADJUSTMENT,
                ExecutionStatus.WAITING_CONFIRMATION, "summary", Instant.now());
        when(learning.get("user-1")).thenReturn(base);
        when(roadmap.currentMapIfPresent("user-1")).thenReturn(Optional.of(map));
        when(wrongQuestions.summary("user-1")).thenReturn(wrong);
        when(notifications.list("user-1")).thenReturn(List.of(unread));
        when(governance.listExecutions("user-1")).thenReturn(List.of(pending));
        when(artifacts.workspaces("user-1")).thenReturn(List.of());

        AgentLearningContextService.AgentLearningContext result = service.get("user-1");

        assertEquals(base, result.learning());
        assertEquals(map, result.roadmap());
        assertEquals(3, result.wrongQuestions().activeCount());
        assertEquals(1, result.unreadNotificationCount());
        assertEquals(1, result.pendingConfirmationCount());
        verify(learning).get("user-1");
        verify(roadmap).currentMapIfPresent("user-1");
        verify(wrongQuestions).summary("user-1");
        verify(notifications).list("user-1");
        verify(governance).listExecutions("user-1");
        verify(artifacts).workspaces("user-1");
    }

    @Test
    void representsMissingRoadmapAsARecoverableWarning() {
        when(learning.get("user-2")).thenReturn(new InternalLearningContextResponse(
                "Asia/Shanghai", List.of(), List.of(), List.of(), List.of(), List.of()));
        when(roadmap.currentMapIfPresent("user-2")).thenReturn(Optional.empty());
        when(wrongQuestions.summary("user-2"))
                .thenReturn(new WrongQuestionSummaryResponse(0, 0, List.of(), null));
        when(notifications.list("user-2")).thenReturn(List.of());
        when(governance.listExecutions("user-2")).thenReturn(List.of());
        when(artifacts.workspaces("user-2")).thenReturn(List.of());

        AgentLearningContextService.AgentLearningContext result = service.get("user-2");

        assertNull(result.roadmap());
        assertTrue(result.warnings().contains("尚未加入学习路线，请先在学习路线页面选择路线"));
    }
}
