package com.moxiao.studypilot.assessment.api;

import com.moxiao.studypilot.assessment.application.QuizService;
import com.moxiao.studypilot.auth.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/mastery")
public class MasteryController {

    private final QuizService service;

    public MasteryController(QuizService service) {
        this.service = service;
    }

    @GetMapping
    public List<MasteryResponse> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.listMastery(user.id()).stream().map(MasteryResponse::from).toList();
    }
}
