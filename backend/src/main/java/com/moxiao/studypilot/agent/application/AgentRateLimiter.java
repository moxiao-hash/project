package com.moxiao.studypilot.agent.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 单实例内存滑动窗口限流器。
 *
 * <p>每个用户拥有独立队列；同一队列的清理、计数和写入在同一个锁内完成，
 * 因此并发请求不会同时越过上限。多实例部署应在后续改用 Redis。</p>
 */
public final class AgentRateLimiter {

    private final int limit;
    private final Duration window;
    private final Supplier<Instant> clock;
    private final ConcurrentHashMap<String, Deque<Instant>> requests = new ConcurrentHashMap<>();

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
        Deque<Instant> queue = requests.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (queue) {
            Instant cutoff = now.minus(window);
            while (!queue.isEmpty() && !queue.peekFirst().isAfter(cutoff)) {
                queue.removeFirst();
            }
            if (queue.size() >= limit) {
                long retryAfter = Duration.between(
                        now,
                        queue.peekFirst().plus(window)
                ).toSeconds();
                return new Decision(false, Math.max(1, retryAfter));
            }
            queue.addLast(now);
            return new Decision(true, 0);
        }
    }

    public record Decision(boolean allowed, long retryAfterSeconds) {
    }
}
