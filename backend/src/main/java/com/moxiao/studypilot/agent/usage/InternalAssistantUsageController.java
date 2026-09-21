package com.moxiao.studypilot.agent.usage;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    /**
     * 真实 provider 调用前预占一个许可；{@code allowed=false} 时 AI 侧不得调用模型。
     *
     * <p>预占与用量回调共用同一个 {@code usageId}，因此预占重试与重复回调都幂等。</p>
     */
    @PostMapping("/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    public AssistantBudgetPermitResponse reserve(
            @Valid @RequestBody ReserveAssistantUsageRequest request) {
        return AssistantBudgetPermitResponse.from(
                service.reserve(request.toCommand(), Instant.now()));
    }

    /**
     * 调用失败或未产生用量时释放预占；重复释放返回 {@code released=false}。
     *
     * <p>必须带上 ownerId：只按 id 释放会允许一个 owner 释放另一个 owner 的许可。</p>
     */
    @PostMapping("/reservations/{reservationId}/release")
    public AssistantUsageReservationReleaseResponse release(
            @PathVariable("reservationId") @NotBlank String reservationId,
            @RequestParam("ownerId") @NotBlank String ownerId) {
        return new AssistantUsageReservationReleaseResponse(
                reservationId, service.release(reservationId, ownerId, Instant.now()));
    }

    /**
     * 幂等键已属于其他 owner 时的稳定机器可读冲突。
     *
     * <p>返回 409 而不是静默改写：跨 owner 的终结/释放不是"幂等重试"。</p>
     */
    @ExceptionHandler(AssistantUsageOwnerConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public AssistantUsageConflictResponse ownerConflict(AssistantUsageOwnerConflictException conflict) {
        return AssistantUsageConflictResponse.from(conflict);
    }

    @GetMapping("/budget")
    public AssistantBudgetResponse budget(@RequestParam("ownerId") @NotBlank String ownerId) {
        return AssistantBudgetResponse.from(service.checkBudget(ownerId, Instant.now()));
    }
}
