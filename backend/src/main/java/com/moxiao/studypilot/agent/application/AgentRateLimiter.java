package com.moxiao.studypilot.agent.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 单实例内存滑动窗口限流器。
 *
 * <p>每个用户拥有独立队列；ConcurrentHashMap.compute 串行化同一用户的清理、
 * 计数和写入，因此并发请求不会同时越过上限。每个窗口触发一次惰性全局清理，
 * 避免不活跃用户的空桶无限增长。多实例部署应在后续改用 Redis。</p>
 */
public final class AgentRateLimiter {

    private final int limit;
    private final Duration window;
    private final Supplier<Instant> clock;
    private final ConcurrentHashMap<String, Deque<Instant>> requests = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> nextSweepAt = new AtomicReference<>();

    public AgentRateLimiter(int limit, Duration window) {
        this(limit, window, Instant::now);
    }

    AgentRateLimiter(int limit, Duration window, Supplier<Instant> clock) {
        if (limit < 1 || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("limit 和 window 必须为正数");
        }
        this.limit = limit;
        this.window = window;
        this.clock = clock;
    }

    public Decision tryAcquire(String key) {
        Instant now = clock.get();
        sweepIfDue(now);
        AtomicReference<Decision> result = new AtomicReference<>();
        requests.compute(key, (ignored, existing) -> {
            Deque<Instant> queue = existing != null ? existing : new ArrayDeque<>();
            Instant cutoff = now.minus(window);
            removeExpired(queue, cutoff);
            if (queue.size() >= limit) {
                long retryAfter = Duration.between(
                        now,
                        queue.peekFirst().plus(window)
                ).toSeconds();
                result.set(new Decision(false, Math.max(1, retryAfter)));
                return queue;
            }
            queue.addLast(now);
            result.set(new Decision(true, 0));
            return queue;
        });
        return result.get();
    }

    private void sweepIfDue(Instant now) {
        while (true) {
            Instant scheduled = nextSweepAt.get();
            if (scheduled != null && now.isBefore(scheduled)) {
                return;
            }
            if (nextSweepAt.compareAndSet(scheduled, now.plus(window))) {
                Instant cutoff = now.minus(window);
                requests.forEach((key, ignored) ->
                        requests.computeIfPresent(key, (currentKey, queue) -> {
                            removeExpired(queue, cutoff);
                            return queue.isEmpty() ? null : queue;
                        })
                );
                return;
            }
        }
    }

    private void removeExpired(Deque<Instant> queue, Instant cutoff) {
        while (!queue.isEmpty() && !queue.peekFirst().isAfter(cutoff)) {
            queue.removeFirst();
        }
    }

    int bucketCount() {
        return requests.size();
    }

    public record Decision(boolean allowed, long retryAfterSeconds) {
    }
}
