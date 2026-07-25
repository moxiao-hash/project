package com.moxiao.studypilot.learning.api;

import com.moxiao.studypilot.learning.application.ConfirmedLearningPlanService;
import com.moxiao.studypilot.learning.application.InternalLearningContextService;
import com.moxiao.studypilot.learning.application.LearningPlanService;
import com.moxiao.studypilot.learning.application.LearningTaskService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/internal")
public class InternalLearningToolController {

    private final InternalLearningContextService contextService;
    private final LearningPlanService planService;
    private final ConfirmedLearningPlanService confirmedPlanService;
    private final LearningTaskService taskService;

    public InternalLearningToolController(
            InternalLearningContextService contextService,
            LearningPlanService planService,
            ConfirmedLearningPlanService confirmedPlanService,
            LearningTaskService taskService
    ) {
        this.contextService = contextService;
        this.planService = planService;
        this.confirmedPlanService = confirmedPlanService;
        this.taskService = taskService;
    }

    @GetMapping("/users/{ownerId}/learning-context")
    public InternalLearningContextResponse context(@PathVariable String ownerId) {
        return contextService.get(ownerId);
    }

    /**
     * 为 Agent 返回某个用户在指定日期的任务。
     *
     * <p>查询仍然通过 LearningTaskService 按 ownerId 隔离数据，Controller 只负责
     * HTTP 参数转换和响应映射，避免内部接口绕过现有业务边界直接访问 Repository。</p>
     */
    @GetMapping("/users/{ownerId}/learning-tasks")
    public List<LearningTaskResponse> tasks(
            @PathVariable String ownerId,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return taskService.list(ownerId, date).stream()
                .map(LearningTaskResponse::from)
                .toList();
    }

    @PostMapping("/learning-plans")
    @ResponseStatus(HttpStatus.CREATED)
    public LearningPlanResponse createPlanDraft(
            @Valid @RequestBody InternalCreateLearningPlanRequest request
    ) {
        return LearningPlanResponse.from(
                planService.create(request.ownerId(), request.toPlanRequest())
        );
    }

    @PostMapping("/confirmed-learning-plans")
    @ResponseStatus(HttpStatus.CREATED)
    public ConfirmedLearningPlanResponse createConfirmedPlan(
            @Valid @RequestBody CreateConfirmedLearningPlanRequest request
    ) {
        return confirmedPlanService.create(request);
    }
}
