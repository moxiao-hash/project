package com.moxiao.studypilot.learning.api;

import com.moxiao.studypilot.auth.security.AuthenticatedUser;
import com.moxiao.studypilot.learning.application.CreateLearningGoalService;
import com.moxiao.studypilot.learning.domain.LearningGoal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/learning-goals")
public class LearningGoalController {

    private final CreateLearningGoalService createLearningGoalService;

    public LearningGoalController(CreateLearningGoalService createLearningGoalService) {
        this.createLearningGoalService = createLearningGoalService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LearningGoalResponse create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateLearningGoalRequest request
    ) {
        LearningGoal goal = createLearningGoalService.create(
                user.id(),
                request.title(),
                request.targetDate(),
                request.weeklyStudyHours()
        );
        return LearningGoalResponse.from(goal);
    }

    @GetMapping
    public List<LearningGoalResponse> list(
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return createLearningGoalService.list(user.id()).stream()
                .map(LearningGoalResponse::from)
                .toList();
    }
}
