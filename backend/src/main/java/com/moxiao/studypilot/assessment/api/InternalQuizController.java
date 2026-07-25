package com.moxiao.studypilot.assessment.api;

import com.moxiao.studypilot.assessment.application.QuizService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/quizzes")
public class InternalQuizController {

    private final QuizService service;

    public InternalQuizController(QuizService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InternalQuizResponse create(@Valid @RequestBody CreateQuizRequest request) {
        QuizService.QuizBundle bundle = service.create(request);
        return InternalQuizResponse.from(bundle.quiz(), bundle.questions());
    }
}
