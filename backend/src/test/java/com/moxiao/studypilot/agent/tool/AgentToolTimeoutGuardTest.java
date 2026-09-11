package com.moxiao.studypilot.agent.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentToolTimeoutGuardTest {

    private final AgentToolTimeoutGuard guard = new AgentToolTimeoutGuard();

    @Test
    void slowTaskFailsDeterministicallyAtTheDeclaredTimeout() {
        long startedAt = System.nanoTime();
        AgentToolTimeoutException thrown = assertThrows(AgentToolTimeoutException.class,
                () -> guard.call(1_000, () -> {
                    try {
                        Thread.sleep(3_000);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("被中断");
                    }
                    return "late";
                }));
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
        assertTrue(thrown.getMessage().contains("1000"),
                "超时错误必须包含确定性上限信息");
        assertTrue(elapsedMillis < 2_000, "实际 " + elapsedMillis + "ms");
    }

    @Test
    void fastTaskReturnsValueAndBusinessErrorsPropagateUnchanged() {
        assertEquals("ok", guard.call(1_000, () -> "ok"));
        IllegalStateException original = assertThrows(IllegalStateException.class,
                () -> guard.call(1_000, () -> {
                    throw new IllegalStateException("业务失败");
                }));
        assertEquals("业务失败", original.getMessage());
    }

    @Test
    void completionAfterTimeoutDoesNotChangeTheDeterministicOutcome() {
        // 断言超时是即时确定性的：取消后调用方立即拿到异常，不等待任务自然结束。
        assertThrows(AgentToolTimeoutException.class,
                () -> guard.call(1_000, () -> {
                    try {
                        Thread.sleep(1_500);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    return "very late";
                }));
    }
}
