package com.moxiao.studypilot.aicredential.api;

import com.moxiao.studypilot.aicredential.application.AiCredentialService;
import com.moxiao.studypilot.aicredential.domain.AiProvider;
import com.moxiao.studypilot.auth.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai-settings")
public class AiCredentialController {

    private final AiCredentialService service;

    public AiCredentialController(AiCredentialService service) {
        this.service = service;
    }

    @GetMapping
    public AiSettingsResponse get(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.settings(user.id());
    }

    @PutMapping("/deepseek-key")
    public AiSettingsResponse putDeepseek(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody UpdateAiCredentialRequest request
    ) {
        return service.save(user.id(), AiProvider.DEEPSEEK, request.apiKey());
    }

    @DeleteMapping("/deepseek-key")
    public AiSettingsResponse deleteDeepseek(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.delete(user.id(), AiProvider.DEEPSEEK);
    }

    @PutMapping("/tavily-key")
    public AiSettingsResponse putTavily(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody UpdateAiCredentialRequest request
    ) {
        return service.save(user.id(), AiProvider.TAVILY, request.apiKey());
    }

    @DeleteMapping("/tavily-key")
    public AiSettingsResponse deleteTavily(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.delete(user.id(), AiProvider.TAVILY);
    }
}
