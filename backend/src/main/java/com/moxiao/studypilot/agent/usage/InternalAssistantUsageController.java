package com.moxiao.studypilot.agent.usage;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * AI 服务调用的用量内部接口。
 *
 * <p>与其它 {@code /internal/**} 一样由 {@code InternalServiceTokenFilter} 校验内部令牌；
 * {@code ownerId} 只作为归属字段落库，模型无法通过本接口决定自己的 owner。</p>
 */
@RestController
@RequestMapping("/internal/assistant-usage")
public class InternalAssistantUsageController {

    private final AssistantUsageService service;

    public InternalAssistantUsageController(AssistantUsageService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AssistantUsageResponse record(@Valid @RequestBody RecordAssistantUsageRequest request) {
        return AssistantUsageResponse.from(service.record(request.toCommand()));
    }

    @GetMapping("/budget")
    public AssistantBudgetResponse budget(@RequestParam("ownerId") @NotBlank String ownerId) {
        return AssistantBudgetResponse.from(service.checkBudget(ownerId, Instant.now()));
    }
}
