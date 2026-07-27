package com.moxiao.studypilot.assessment.api;

import com.moxiao.studypilot.assessment.application.QuizService;
import com.moxiao.studypilot.auth.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;

import java.util.List;

@RestController
@RequestMapping("/api/quiz-attempts")
public class QuizAttemptController {
    private final QuizService service;

    public QuizAttemptController(QuizService service) {
        this.service = service;
    }

    @GetMapping("/{attemptId}")
    public QuizAttemptResponse get(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String attemptId
    ) {
        return service.getAttempt(user.id(), attemptId);
    }

    @PostMapping("/{attemptId}/self-assessments")
    public List<MasteryResponse> selfAssess(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String attemptId,
            @Valid @RequestBody SelfAssessmentRequest request
    ) {
        return service.recordSelfAssessments(user.id(), attemptId, request).stream()
                .map(MasteryResponse::from)
                .toList();
    }
}
