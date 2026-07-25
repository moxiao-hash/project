package com.moxiao.studypilot.learning.api;

import com.moxiao.studypilot.learning.application.CreateLearningGoalService;
import com.moxiao.studypilot.learning.domain.LearningGoal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/learning-goals")
public class LearningGoalController {

    private final CreateLearningGoalService createLearningGoalService;

    public LearningGoalController(CreateLearningGoalService createLearningGoalService) {
        this.createLearningGoalService = createLearningGoalService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LearningGoalResponse create(@Valid @RequestBody CreateLearningGoalRequest request) {
        LearningGoal goal = createLearningGoalService.create(
                request.title(),
                request.targetDate(),
                request.weeklyStudyHours()
        );
        return LearningGoalResponse.from(goal);
    }
}
