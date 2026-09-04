package com.moxiao.studypilot.agent.tool;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

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
}
