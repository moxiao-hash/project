package com.moxiao.studypilot.aicredential.api;

import com.moxiao.studypilot.aicredential.application.AiCredentialService;
import com.moxiao.studypilot.aicredential.domain.AiProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/ai-credentials")
public class InternalAiCredentialController {

    private final AiCredentialService service;

    public InternalAiCredentialController(AiCredentialService service) {
        this.service = service;
    }

    @GetMapping("/{provider}")
    public RuntimeCredentialResponse resolve(
            @PathVariable String provider,
            @RequestParam String ownerId
    ) {
        return new RuntimeCredentialResponse(
                service.resolveUserKey(ownerId, AiProvider.fromPath(provider))
        );
    }

    public record RuntimeCredentialResponse(String apiKey) {
    }
}
