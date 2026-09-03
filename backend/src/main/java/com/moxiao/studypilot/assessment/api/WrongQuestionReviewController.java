package com.moxiao.studypilot.assessment.api;

import com.moxiao.studypilot.assessment.application.WrongQuestionReviewService;
import com.moxiao.studypilot.auth.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wrong-question-reviews")
public class WrongQuestionReviewController {
    private final WrongQuestionReviewService service;

    public WrongQuestionReviewController(WrongQuestionReviewService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WrongQuestionReviewResponse create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateWrongQuestionReviewRequest request
    ) {
        return service.create(user.id(), request);
    }

    @GetMapping("/current")
    public ResponseEntity<WrongQuestionReviewResponse> current(
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return service.current(user.id()).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
