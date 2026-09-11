package com.moxiao.studypilot.agent.tool;

/**
 * Task 30：执行租约已被恢复流程接管。
 *
 * <p>业务事务在写入 SUCCEEDED 时发现租约已失效，必须抛出以回滚整个事务，
 * 避免“业务副作用已提交但动作状态不确定”。</p>
 */
public class AgentToolActionLeaseLostException extends RuntimeException {

    public AgentToolActionLeaseLostException(String message) {
        super(message);
    }
}
