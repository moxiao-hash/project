package com.moxiao.studypilot.learning.api;

import com.moxiao.studypilot.learning.application.LearningAdaptationContextService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/internal/users/{ownerId}/adaptation-context")
public class InternalAdaptationContextController {

    private final LearningAdaptationContextService service;

    public InternalAdaptationContextController(
            LearningAdaptationContextService service
    ) {
        this.service = service;
    }

    @GetMapping
    public InternalAdaptationContextResponse get(
            @PathVariable String ownerId,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate analysisDate,
            @RequestParam(defaultValue = "14") int windowDays
    ) {
        if (windowDays < 1 || windowDays > 30) {
            throw new IllegalArgumentException("聚合窗口必须在 1 到 30 天之间");
        }
        return service.get(ownerId, analysisDate, windowDays);
    }
}
