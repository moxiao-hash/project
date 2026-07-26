package com.moxiao.studypilot.learning.api;

import com.moxiao.studypilot.learning.application.PlanAdjustmentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/internal/plan-adjustments")
public class InternalPlanAdjustmentController {

    private final PlanAdjustmentService service;
    private final ObjectMapper objectMapper;

    public InternalPlanAdjustmentController(
            PlanAdjustmentService service,
            ObjectMapper objectMapper
    ) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlanAdjustmentResponse create(
            @Valid @RequestBody CreatePlanAdjustmentRequest request
    ) {
        return PlanAdjustmentResponse.from(service.create(request), objectMapper);
    }

    @GetMapping("/{adjustmentId}")
    public PlanAdjustmentResponse get(@PathVariable String adjustmentId) {
        return PlanAdjustmentResponse.from(service.get(adjustmentId), objectMapper);
    }

    @PostMapping("/{adjustmentId}/execute")
    public PlanAdjustmentResponse execute(
            @PathVariable String adjustmentId,
            @Valid @RequestBody ExecutePlanAdjustmentRequest request
    ) {
        return PlanAdjustmentResponse.from(
                service.execute(adjustmentId, request),
                objectMapper
        );
    }
}
