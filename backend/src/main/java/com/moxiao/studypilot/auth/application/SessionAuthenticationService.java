package com.moxiao.studypilot.auth.application;

import com.moxiao.studypilot.auth.infrastructure.UserAccountJpaRepository;
import com.moxiao.studypilot.auth.infrastructure.UserSessionJpaRepository;
import com.moxiao.studypilot.auth.security.AuthenticatedUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
public class SessionAuthenticationService {

    private final UserSessionJpaRepository sessionRepository;
    private final UserAccountJpaRepository userRepository;

    public SessionAuthenticationService(
            UserSessionJpaRepository sessionRepository,
            UserAccountJpaRepository userRepository
    ) {
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public Optional<AuthenticatedUser> authenticate(String rawToken) {
        return sessionRepository.findById(SessionTokenService.hash(rawToken))
                .filter(session -> session.getExpiresAt().isAfter(Instant.now()))
                .flatMap(session -> userRepository.findById(session.getUserId()))
                .map(user -> new AuthenticatedUser(
                        user.getId(),
                        user.getEmail(),
                        user.getDisplayName()
                ));
    }
}
