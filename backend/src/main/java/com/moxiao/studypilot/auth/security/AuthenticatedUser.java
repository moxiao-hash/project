package com.moxiao.studypilot.auth.security;

public record AuthenticatedUser(
        String id,
        String email,
        String displayName
) {
}
