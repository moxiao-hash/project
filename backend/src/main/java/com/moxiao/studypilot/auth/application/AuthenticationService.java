package com.moxiao.studypilot.auth.application;

import com.moxiao.studypilot.auth.api.AuthResponse;
import com.moxiao.studypilot.auth.api.UserResponse;
import com.moxiao.studypilot.auth.infrastructure.UserAccountEntity;
import com.moxiao.studypilot.auth.infrastructure.UserAccountJpaRepository;
import com.moxiao.studypilot.shared.error.ConflictException;
import com.moxiao.studypilot.shared.error.InvalidCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthenticationService {

    private final UserAccountJpaRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SessionTokenService sessionTokenService;

    public AuthenticationService(
            UserAccountJpaRepository userRepository,
            PasswordEncoder passwordEncoder,
            SessionTokenService sessionTokenService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.sessionTokenService = sessionTokenService;
    }

    @Transactional
    public AuthResponse register(String email, String password, String displayName) {
        String normalizedEmail = normalizeEmail(email);
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new ConflictException("该邮箱已注册");
        }
        UserAccountEntity user = userRepository.save(new UserAccountEntity(
                UUID.randomUUID().toString(),
                normalizedEmail,
                passwordEncoder.encode(password),
                displayName.trim(),
                Instant.now()
        ));
        return issueResponse(user);
    }

    @Transactional
    public AuthResponse login(String email, String password) {
        UserAccountEntity user = userRepository.findByEmail(normalizeEmail(email))
                .orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        return issueResponse(user);
    }

    @Transactional
    public void logout(String rawToken) {
        sessionTokenService.revoke(rawToken);
    }

    private AuthResponse issueResponse(UserAccountEntity user) {
        SessionTokenService.IssuedToken token = sessionTokenService.issue(user.getId());
        return new AuthResponse(
                token.value(),
                "Bearer",
                token.expiresAt(),
                UserResponse.from(user)
        );
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
