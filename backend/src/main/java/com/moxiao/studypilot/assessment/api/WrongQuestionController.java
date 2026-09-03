package com.moxiao.studypilot.assessment.api;

import com.moxiao.studypilot.assessment.application.WrongQuestionService;
import com.moxiao.studypilot.assessment.domain.WrongQuestionStatus;
import com.moxiao.studypilot.auth.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.moxiao.studypilot.assessment.api.WrongQuestionSummaryResponse;

@RestController
@RequestMapping("/api/wrong-questions")
public class WrongQuestionController {
    private final WrongQuestionService service;

    public WrongQuestionController(WrongQuestionService service) {
        this.service = service;
    }

    @GetMapping
    public WrongQuestionPageResponse list(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "ACTIVE") WrongQuestionStatus status,
            @RequestParam(required = false) String chapterKey,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.list(user.id(), status, chapterKey, page, size);
    }

    @GetMapping("/summary")
    public WrongQuestionSummaryResponse summary(
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return service.summary(user.id());
    }
}
