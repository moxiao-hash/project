package com.moxiao.studypilot.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class InternalServiceTokenFilter extends OncePerRequestFilter {

    private static final String TOKEN_HEADER = "X-Internal-Service-Token";

    private final byte[] expectedToken;

    public InternalServiceTokenFilter(
            @Value("${studypilot.internal-service-token:}") String expectedToken
    ) {
        this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String provided = request.getHeader(TOKEN_HEADER);
        boolean valid = provided != null
                && expectedToken.length > 0
                && MessageDigest.isEqual(
                        expectedToken,
                        provided.getBytes(StandardCharsets.UTF_8)
                );
        if (!valid) {
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "内部服务令牌无效");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
