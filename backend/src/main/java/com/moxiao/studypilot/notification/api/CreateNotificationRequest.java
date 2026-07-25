package com.moxiao.studypilot.notification.api;

import com.moxiao.studypilot.notification.domain.NotificationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateNotificationRequest(
        @NotBlank String ownerId,
        @NotNull NotificationType type,
        @NotBlank @Size(max = 160) String title,
        @NotBlank @Size(max = 1000) String content
) {
}
