package com.moxiao.studypilot.agent.tool;

/**
 * Task 30：工具执行超过 {@link AgentToolDescriptor#timeoutMillis()} 时的确定性异常。
 *
 * <p>只读链路映射为 HTTP 504；写工具在治理事务内被记录为 FAILED 动作，
 * 不会因为超时而把失败当作成功。</p>
 */
public class AgentToolTimeoutException extends RuntimeException {

    public AgentToolTimeoutException(String message) {
        super(message);
    }
}
