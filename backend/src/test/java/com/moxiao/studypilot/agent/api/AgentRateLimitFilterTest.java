package com.moxiao.studypilot.agent.api;

import com.moxiao.studypilot.agent.application.AgentRateLimitFilter;
import com.moxiao.studypilot.agent.application.AgentRateLimiter;
import com.moxiao.studypilot.auth.security.AuthenticatedUser;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRateLimitFilterTest {

    @Test
    void modelRoutesUseStricterLimitAndReturnStableJson() throws Exception {
        AgentRateLimitFilter filter = new AgentRateLimitFilter(
                new AgentRateLimiter(30, Duration.ofMinutes(1)),
                new AgentRateLimiter(1, Duration.ofMinutes(1))
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthenticatedUser("user-1", "a@example.com", "A"),
                        "token",
                        List.of()
                )
        );
        try {
            MockHttpServletRequest first = new MockHttpServletRequest(
                    "POST",
                    "/api/assistant/conversations/id/messages"
            );
            filter.doFilter(first, new MockHttpServletResponse(), (req, res) -> {
            });

            MockHttpServletRequest second = new MockHttpServletRequest(
                    "POST",
                    "/api/assistant/conversations/id/messages"
            );
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicInteger calls = new AtomicInteger();
            filter.doFilter(second, response, (req, res) -> calls.incrementAndGet());

            assertEquals(429, response.getStatus());
            assertTrue(Integer.parseInt(response.getHeader("Retry-After")) >= 1);
            assertTrue(response.getContentType().startsWith("application/json"));
            assertTrue(response.getContentAsString().contains("RATE_LIMITED"));
            assertEquals(0, calls.get());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void nonAgentRoutesAreNotLimited() throws Exception {
        AgentRateLimitFilter filter = new AgentRateLimitFilter(
                new AgentRateLimiter(1, Duration.ofMinutes(1)),
                new AgentRateLimiter(1, Duration.ofMinutes(1))
        );
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        AtomicInteger calls = new AtomicInteger();

        filter.doFilter(
                request,
                new MockHttpServletResponse(),
                (req, res) -> calls.incrementAndGet()
        );

        assertEquals(1, calls.get());
    }
}
