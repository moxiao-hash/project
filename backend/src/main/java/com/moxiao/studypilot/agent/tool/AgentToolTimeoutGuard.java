package com.moxiao.studypilot.agent.tool;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Task 30：在运行时强制 {@link AgentToolDescriptor#timeoutMillis()} 的有界执行护栏。
 *
 * <p>工具调用在专用守护线程池中执行，超过声明上限即取消并以
 * {@link AgentToolTimeoutException} 确定性失败。事务边界仍由 Spring 代理在被调度线程上创建
 * （读工具的服务方法是 {@code @Transactional}，写工具的
 * {@code AgentToolBusinessExecutor.execute} 是 {@code REQUIRES_NEW}），
 * 因此超时不会把未提交的写操作标记为成功。</p>
 *
 * <p>已知限制：JDBC / 阻塞 IO 不一定响应线程中断；超时后工作线程会在底层调用返回时结束，
 * 其事务随线程回滚或提交由底层驱动决定。护栏保证调用方在超时点得到确定性失败，
 * 而不是无限等待。</p>
 */
@Service
public class AgentToolTimeoutGuard {

    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private final ExecutorService executor;

    public AgentToolTimeoutGuard() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(
                    runnable, "agent-tool-timeout-" + THREAD_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newCachedThreadPool(factory);
    }

    public <T> T call(int timeoutMillis, Supplier<T> task) {
        int boundedTimeout = Math.max(1, timeoutMillis);
        Future<T> future = executor.submit(task::get);
        try {
            return future.get(boundedTimeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new AgentToolTimeoutException(
                    "Agent 工具执行超时（上限 " + boundedTimeout + "ms）");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new AgentToolTimeoutException("Agent 工具执行被中断");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Agent 工具执行失败", cause);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
