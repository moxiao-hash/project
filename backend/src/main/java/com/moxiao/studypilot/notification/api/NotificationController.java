package com.moxiao.studypilot.notification.api;

import com.moxiao.studypilot.auth.security.AuthenticatedUser;
import com.moxiao.studypilot.notification.application.NotificationService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public List<NotificationResponse> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.list(user.id()).stream().map(NotificationResponse::from).toList();
    }

    @PatchMapping("/{notificationId}/read")
    public NotificationResponse markRead(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String notificationId
    ) {
        return NotificationResponse.from(service.markRead(user.id(), notificationId));
    }
}
