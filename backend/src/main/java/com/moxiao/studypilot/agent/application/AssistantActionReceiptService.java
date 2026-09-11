package com.moxiao.studypilot.agent.application;

import com.moxiao.studypilot.agent.api.AssistantActionReceiptValidator;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

/**
 * Task 30：浏览器动作回执的唯一公共入口。
 *
 * <p>Java 负责：从 Bearer 会话解析 ownerId、严格校验固定请求体、确认会话归属，
 * 然后把 {@code actionId/status/error/currentRoute} 转发给 Python。Python 再根据
 * 自己登记的界面动作校验动作归属、幂等落库并决定结束/有界重试/人工入口。</p>
 */
@Service
public class AssistantActionReceiptService {

    private final AgentGatewayService gateway;
    private final AssistantActionReceiptValidator validator;
    private final ObjectMapper objectMapper;

    public AssistantActionReceiptService(
            AgentGatewayService gateway,
            AssistantActionReceiptValidator validator,
            ObjectMapper objectMapper
    ) {
        this.gateway = gateway;
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    public JsonNode record(UUID conversationId, String ownerId, JsonNode body) {
        AssistantActionReceiptValidator.Receipt receipt = validator.validate(body);
        // 会话归属由权威存储确认：跨用户/未知会话在这里就以 404 拒绝，
        // 不写入任何回执，再进入 owner 作用域的内部转发。
        gateway.get("/internal/assistant/conversations/" + conversationId, ownerId);
        ObjectNode forwarded = objectMapper.createObjectNode()
                .put("actionId", receipt.actionId())
                .put("status", receipt.status())
                .put("currentRoute", receipt.currentRoute());
        if (receipt.error() != null) {
            forwarded.put("error", receipt.error());
        }
        return gateway.post(
                "/internal/assistant/conversations/" + conversationId + "/actions/receipt",
                forwarded,
                ownerId
        ).body();
    }
}
