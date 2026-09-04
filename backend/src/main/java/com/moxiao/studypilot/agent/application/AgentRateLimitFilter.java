package com.moxiao.studypilot.agent.application;

import com.moxiao.studypilot.auth.security.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

/**
 * 公共 Agent 门面的用户级限流边界。
 */
@Component
public class AgentRateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> MODEL_ROUTE_SUFFIXES = Set.of(
            "/messages",
            "/quizzes/generate",
            "/plan-adjustments/analyze"
    );

    private final AgentRateLimiter allAgentRequests;
    private final AgentRateLimiter modelRequests;

    public AgentRateLimitFilter() {
        this(
                new AgentRateLimiter(30, Duration.ofMinutes(1)),
                new AgentRateLimiter(10, Duration.ofMinutes(1))
        );
    }

    public AgentRateLimitFilter(
            AgentRateLimiter allAgentRequests,
            AgentRateLimiter modelRequests
    ) {
        this.allAgentRequests = allAgentRequests;
        this.modelRequests = modelRequests;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/agent/") && !path.startsWith("/api/assistant/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            filterChain.doFilter(request, response);
            return;
        }

        AgentRateLimiter.Decision allDecision = allAgentRequests.tryAcquire(user.id());
        if (!allDecision.allowed()) {
            reject(response, allDecision.retryAfterSeconds());
            return;
        }
        if (triggersModel(request)) {
            AgentRateLimiter.Decision modelDecision = modelRequests.tryAcquire(user.id());
            if (!modelDecision.allowed()) {
                reject(response, modelDecision.retryAfterSeconds());
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private boolean triggersModel(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        return MODEL_ROUTE_SUFFIXES.stream().anyMatch(path::endsWith);
    }

    private void reject(HttpServletResponse response, long retryAfter) throws IOException {
        byte[] body = """
                {"code":"RATE_LIMITED","message":"请求过于频繁，请稍后重试"}
                """.strip().getBytes(StandardCharsets.UTF_8);
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retryAfter));
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }
}
