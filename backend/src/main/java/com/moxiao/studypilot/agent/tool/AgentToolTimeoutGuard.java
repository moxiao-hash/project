package com.moxiao.studypilot.agent.tool;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Task 30：在运行时强制 {@link AgentToolDescriptor#timeoutMillis()} 的有界执行护栏。
 *
 * <p>读工具使用 {@link #call(int, Supplier)}：超时即取消并以
 * {@link AgentToolTimeoutException} 确定性失败（只读无副作用）。</p>
 *
 * <p>写工具使用 {@link #callFenced(int, Supplier, Consumer)}：写事务可能忽略线程中断并稍后提交，
 * 因此超时后先在宽限期内等待事务真正结束；若结果已知则按真实结果返回；若仍未结束则返回
 * {@link FencedResult#running()}，由调用方保持非终态并注册延迟定稿回调，
 * <b>绝不</b>在事务仍可能提交时报告终态失败。</p>
 *
 * <p>线程资源有界：最多 {@value #MAX_THREADS} 个守护线程，超出即拒绝而不是无限堆积。</p>
 */
@Service
public class AgentToolTimeoutGuard {

    /** 默认宽限期：超时后等待写事务真正结束的最长时间。 */
    public static final long DEFAULT_GRACE_MILLIS = 5_000L;

    private static final int MAX_THREADS = 64;
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private final ExecutorService executor;
    private final long graceMillis;

    public AgentToolTimeoutGuard() {
        this(DEFAULT_GRACE_MILLIS);
    }

    @Autowired
    public AgentToolTimeoutGuard(
            @Value("${studypilot.agent-tool-timeout-grace-millis:5000}") long graceMillis
    ) {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(
                    runnable, "agent-tool-timeout-" + THREAD_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.executor = new ThreadPoolExecutor(
                0, MAX_THREADS, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(), factory, new ThreadPoolExecutor.AbortPolicy());
        this.graceMillis = Math.max(1L, graceMillis);
    }

    /** 只读路径：超时即确定性失败，调用方不会拿到半成品数据。 */
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
            throw unwrap(exception);
        }
    }

    /**
     * 写路径：安全完成语义。
     *
     * @param onLateCompletion 当事务在宽限期后仍未结束时，注册完成回调；
     *                         回调只会在事务真正结束后触发（SUCCEEDED/FAILED 之一）。
     */
    public <T> FencedResult<T> callFenced(
            int timeoutMillis,
            Supplier<T> task,
            Consumer<FencedResult<T>> onLateCompletion
    ) {
        int boundedTimeout = Math.max(1, timeoutMillis);
        CompletableFuture<T> completion = new CompletableFuture<>();
        Future<?> handle = executor.submit(() -> {
            try {
                completion.complete(task.get());
            } catch (Throwable error) {
                completion.completeExceptionally(error);
            }
        });
        try {
            return FencedResult.completed(completion.get(boundedTimeout, TimeUnit.MILLISECONDS));
        } catch (TimeoutException timeout) {
            // 事务可能忽略中断继续执行：先等待它真正结束，避免“先报失败、后提交”。
            handle.cancel(true);
            try {
                return FencedResult.completed(completion.get(graceMillis, TimeUnit.MILLISECONDS));
            } catch (TimeoutException unresolved) {
                if (onLateCompletion != null) {
                    completion.whenComplete((value, error) -> onLateCompletion.accept(
                            error == null
                                    ? FencedResult.completed(value)
                                    : FencedResult.failed(unwrap(error))));
                }
                return FencedResult.unresolved();
            } catch (ExecutionException failure) {
                return FencedResult.failed(unwrap(failure));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return FencedResult.failed(
                        new AgentToolTimeoutException("Agent 工具执行被中断"));
            }
        } catch (ExecutionException failure) {
            return FencedResult.failed(unwrap(failure));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return FencedResult.failed(new AgentToolTimeoutException("Agent 工具执行被中断"));
        }
    }

    private static RuntimeException unwrap(Throwable error) {
        Throwable cause = error;
        if ((error instanceof ExecutionException || error instanceof CompletionException)
                && error.getCause() != null) {
            cause = error.getCause();
        }
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (cause instanceof Error fatal) {
            throw fatal;
        }
        return new IllegalStateException("Agent 工具执行失败", cause);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /**
     * 写工具的执行结果。{@code running()} 表示事务结果未知，调用方必须保持非终态。
     */
    public record FencedResult<T>(T value, RuntimeException error, boolean running) {

        public static <T> FencedResult<T> completed(T value) {
            return new FencedResult<>(value, null, false);
        }

        public static <T> FencedResult<T> failed(RuntimeException error) {
            return new FencedResult<>(null, error, false);
        }

        public static <T> FencedResult<T> unresolved() {
            return new FencedResult<>(null, null, true);
        }
    }
}
