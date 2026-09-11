package com.moxiao.studypilot.agent.tool;

import com.moxiao.studypilot.agent.application.AgentGovernanceService;
import com.moxiao.studypilot.notification.application.NotificationService;
import com.moxiao.studypilot.notification.infrastructure.NotificationEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 30 第三次复审：[UNIT/H2] 恢复定稿的瞬时持久化失败必须整体回滚并可重试。
 *
 * <p>用 Mockito 替换治理协作者制造一次性写失败：恢复事务必须回滚（动作仍为 RUNNING、
 * 不产生通知），下一次恢复成功后恰好收敛一次，不重复副作用。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AgentToolActionRecoveryRollbackTest {

    private static final String RECOVERY_MESSAGE_FRAGMENT = "未完成";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AgentToolActionJpaRepository actionRepository;

    @Autowired
    private AgentToolActionRecoveryService recoveryService;

    @Autowired
    private NotificationService notificationService;

    @MockitoBean
    private AgentGovernanceService governanceService;

    private String ownerId;
    private String actionId;

    @BeforeEach
    void createOrphanRunningAction() throws Exception {
        ownerId = registerUser();
        AgentToolActionEntity action = new AgentToolActionEntity(
                UUID.randomUUID().toString(), ownerId,
                UUID.randomUUID().toString(),
                "recovery-rollback-" + System.nanoTime(), "learning.task.update", 1,
                AgentToolRiskLevel.HIGH, AgentToolActionStatus.READY, "恢复回滚测试",
                "{}", Instant.now().plusSeconds(600), Instant.now());
        action.startAttempt("crashed-lease", Instant.now().minusSeconds(300), Instant.now());
        actionRepository.save(action);
        actionId = action.getId();
    }

    @Test
    void transientFinalizationFailureRollsBackAndLaterSucceedsExactlyOnce() {
        when(governanceService.update(anyString(), any()))
                .thenThrow(new RuntimeException("数据库暂时不可用"));

        int failedAttempt = recoveryService.recoverOne(actionId, "instance-a");

        assertEquals(0, failedAttempt, "瞬时失败不得被报告为已恢复");
        assertEquals(AgentToolActionStatus.RUNNING,
                actionRepository.findById(actionId).orElseThrow().getStatus(),
                "定稿事务失败必须整体回滚，动作保持 RUNNING 等待下次恢复");
        assertEquals(0, recoveryNotifications().size(), "回滚不得留下通知");

        doReturn(null).when(governanceService).update(anyString(), any());
        int retry = recoveryService.recoverOne(actionId, "instance-a");

        assertEquals(1, retry, "瞬时失败后重试必须成功收敛");
        assertEquals(AgentToolActionStatus.FAILED,
                actionRepository.findById(actionId).orElseThrow().getStatus());
        assertEquals(1, recoveryNotifications().size(), "收敛后恰好一条恢复通知");
    }

    private List<NotificationEntity> recoveryNotifications() {
        return notificationService.list(ownerId).stream()
                .filter(notification -> notification.getTitle().contains(RECOVERY_MESSAGE_FRAGMENT))
                .toList();
    }

    private String registerUser() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"recovery-rollback-%d@example.com",
                                  "password":"Password123!",
                                  "displayName":"恢复回滚用户"
                                }
                                """.formatted(System.nanoTime())))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("user").get("id").asText();
    }
}
