package com.moxiao.studypilot.agent.api;

import com.moxiao.studypilot.agent.application.AgentGovernanceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/agent-executions")
public class InternalAgentExecutionController {

    private final AgentGovernanceService service;

    public InternalAgentExecutionController(AgentGovernanceService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgentExecutionResponse create(
            @Valid @RequestBody CreateAgentExecutionRequest request
    ) {
        return AgentExecutionResponse.from(service.createExecution(request));
    }

    /**
     * 记录用户已经在 AI 对话中明确确认了本次高风险操作。
     *
     * <p>这个接口只允许携带内部服务令牌的 Python 服务调用；真正的状态转换仍由
     * Java 领域服务完成，因此网页确认与 AI 对话确认共用同一套审计逻辑。</p>
     */
    @PostMapping("/{executionId}/confirm")
    public AgentExecutionResponse confirm(
            @PathVariable String executionId,
            @Valid @RequestBody ConfirmAgentExecutionRequest request
    ) {
        return AgentExecutionResponse.from(service.confirm(request.ownerId(), executionId));
    }

    @PatchMapping("/{executionId}")
    public AgentExecutionResponse update(
            @PathVariable String executionId,
            @Valid @RequestBody UpdateAgentExecutionRequest request
    ) {
        return AgentExecutionResponse.from(service.update(executionId, request));
    }
}
