package com.moxiao.studypilot.agent.automation;

import com.moxiao.studypilot.auth.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/assistant")
public class AssistantAutomationController {

    private final AssistantAutomationService service;

    public AssistantAutomationController(AssistantAutomationService service) {
        this.service = service;
    }

    @PostMapping("/automation-rules")
    @ResponseStatus(HttpStatus.CREATED)
    public AutomationRuleResponse create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateAutomationRuleRequest request
    ) {
        return AutomationRuleResponse.from(service.create(user.id(), request));
    }

    @GetMapping("/automation-rules")
    public List<AutomationRuleResponse> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.list(user.id()).stream().map(AutomationRuleResponse::from).toList();
    }

    @GetMapping("/automation-rules/{ruleId}")
    public AutomationRuleResponse get(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String ruleId
    ) {
        return AutomationRuleResponse.from(service.get(user.id(), ruleId));
    }

    @PatchMapping("/automation-rules/{ruleId}")
    public AutomationRuleResponse update(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String ruleId,
            @RequestBody UpdateAutomationRuleRequest request
    ) {
        return AutomationRuleResponse.from(service.update(user.id(), ruleId, request));
    }

    @DeleteMapping("/automation-rules/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String ruleId
    ) {
        service.delete(user.id(), ruleId);
    }

    @GetMapping("/automation-settings")
    public AutomationSettingsResponse settings(@AuthenticationPrincipal AuthenticatedUser user) {
        return AutomationSettingsResponse.from(service.settings(user.id()));
    }

    @PatchMapping("/automation-settings")
    public AutomationSettingsResponse updateSettings(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody UpdateAutomationSettingsRequest request
    ) {
        return AutomationSettingsResponse.from(
                service.updateSettings(user.id(), request.paused()));
    }
}
