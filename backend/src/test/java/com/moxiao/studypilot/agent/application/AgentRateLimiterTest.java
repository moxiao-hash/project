package com.moxiao.studypilot.agent.application;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRateLimiterTest {

    @Test
    void isolatesUsersAndExpiresTheWindow() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-07-29T00:00:00Z"));
        AgentRateLimiter limiter = new AgentRateLimiter(2, Duration.ofMinutes(1), now::get);

        assertTrue(limiter.tryAcquire("user-a").allowed());
        assertTrue(limiter.tryAcquire("user-a").allowed());
        AgentRateLimiter.Decision rejected = limiter.tryAcquire("user-a");
        assertFalse(rejected.allowed());
        assertTrue(rejected.retryAfterSeconds() >= 1);
        assertTrue(limiter.tryAcquire("user-b").allowed());

        now.set(now.get().plusSeconds(61));
        assertTrue(limiter.tryAcquire("user-a").allowed());
    }

    @Test
    void retryAfterIsStableAndPositive() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-07-29T00:00:00Z"));
        AgentRateLimiter limiter = new AgentRateLimiter(1, Duration.ofMinutes(1), now::get);
        limiter.tryAcquire("user");

        assertEquals(60, limiter.tryAcquire("user").retryAfterSeconds());
    }

    @Test
    void concurrentRequestsCannotExceedLimit() throws Exception {
        AgentRateLimiter limiter = new AgentRateLimiter(10, Duration.ofMinutes(1));
        var executor = Executors.newFixedThreadPool(12);
        try {
            var attempts = java.util.stream.IntStream.range(0, 100)
                    .mapToObj(ignored -> executor.submit(
                            () -> limiter.tryAcquire("same-user").allowed()
                    ))
                    .toList();
            long allowed = 0;
            for (var attempt : attempts) {
                if (attempt.get()) {
                    allowed++;
                }
            }
            assertEquals(10, allowed);
        } finally {
            executor.shutdownNow();
        }
    }
}
