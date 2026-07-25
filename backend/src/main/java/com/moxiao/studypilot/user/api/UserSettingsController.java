package com.moxiao.studypilot.user.api;

import com.moxiao.studypilot.auth.security.AuthenticatedUser;
import com.moxiao.studypilot.user.application.UserSettingsService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user-settings")
public class UserSettingsController {

    private final UserSettingsService service;

    public UserSettingsController(UserSettingsService service) {
        this.service = service;
    }

    @PutMapping
    public UserSettingsResponse update(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody UpdateUserSettingsRequest request
    ) {
        return UserSettingsResponse.from(service.save(user.id(), request));
    }

    @GetMapping
    public UserSettingsResponse get(@AuthenticationPrincipal AuthenticatedUser user) {
        return UserSettingsResponse.from(service.get(user.id()));
    }
}
