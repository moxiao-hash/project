package com.moxiao.studypilot.auth.application;

import com.moxiao.studypilot.auth.infrastructure.UserSessionEntity;
import com.moxiao.studypilot.auth.infrastructure.UserSessionJpaRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

@Service
public class SessionTokenService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserSessionJpaRepository sessionRepository;
    private final long sessionDurationDays;

    public SessionTokenService(
            UserSessionJpaRepository sessionRepository,
            @Value("${studypilot.auth.session-duration-days}") long sessionDurationDays
    ) {
        this.sessionRepository = sessionRepository;
        this.sessionDurationDays = sessionDurationDays;
    }

    public IssuedToken issue(String userId) {
        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(sessionDurationDays, ChronoUnit.DAYS);
        sessionRepository.save(new UserSessionEntity(hash(rawToken), userId, expiresAt, now));
        return new IssuedToken(rawToken, expiresAt);
    }

    public static String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", exception);
        }
    }

    public record IssuedToken(String value, Instant expiresAt) {
    }
}
