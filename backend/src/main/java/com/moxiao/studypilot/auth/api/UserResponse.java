package com.moxiao.studypilot.auth.api;

import com.moxiao.studypilot.auth.infrastructure.UserAccountEntity;

import java.time.Instant;

public record UserResponse(
        String id,
        String email,
        String displayName,
        Instant createdAt
) {

    public static UserResponse from(UserAccountEntity user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getCreatedAt()
        );
    }
}
