package com.moxiao.studypilot.agent.tool;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.util.function.Consumer;

@Service
public class AgentToolBusinessExecutor {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Object execute(
            GovernedAgentToolHandler handler,
            AgentToolContext context,
            JsonNode arguments
    ) {
        return handler.invoke(context, arguments);
    }

    /**
     * Task 30：把业务变更与“动作成功”的持久化放进同一个 {@code REQUIRES_NEW} 事务。
     *
     * <p>业务副作用提交时，动作状态更新/治理/通知必然一起提交；若 {@code onSuccess}
     * 抛出（例如租约已被恢复流程接管），整个事务回滚，避免出现“副作用已提交但动作丢失
     * SUCCEEDED 记录”的不一致。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Object executeAndFinalize(
            GovernedAgentToolHandler handler,
            AgentToolContext context,
            JsonNode arguments,
            Consumer<Object> onSuccess
    ) {
        Object result = handler.invoke(context, arguments);
        onSuccess.accept(result);
        return result;
    }
}
