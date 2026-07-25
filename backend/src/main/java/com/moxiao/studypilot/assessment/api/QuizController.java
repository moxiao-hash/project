package com.moxiao.studypilot.assessment.api;

import com.moxiao.studypilot.assessment.application.QuizService;
import com.moxiao.studypilot.auth.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quizzes")
public class QuizController {

    private final QuizService service;

    public QuizController(QuizService service) {
        this.service = service;
    }

    @GetMapping("/{quizId}")
    public QuizResponse get(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String quizId
    ) {
        QuizService.QuizBundle bundle = service.get(user.id(), quizId);
        return QuizResponse.from(bundle.quiz(), bundle.questions());
    }

    @PostMapping("/{quizId}/attempts")
    @ResponseStatus(HttpStatus.CREATED)
    public QuizAttemptResponse submit(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String quizId,
            @Valid @RequestBody SubmitQuizAttemptRequest request
    ) {
        return service.submit(user.id(), quizId, request);
    }
}
