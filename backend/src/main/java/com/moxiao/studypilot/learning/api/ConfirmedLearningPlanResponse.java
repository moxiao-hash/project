package com.moxiao.studypilot.learning.api;

import com.moxiao.studypilot.learning.infrastructure.LearningPlanEntity;
import com.moxiao.studypilot.learning.infrastructure.LearningTaskEntity;

import java.util.List;

public record ConfirmedLearningPlanResponse(
        LearningPlanResponse plan,
        List<LearningTaskResponse> tasks
) {
    public static ConfirmedLearningPlanResponse from(
            LearningPlanEntity plan,
            List<LearningTaskEntity> tasks
    ) {
        return new ConfirmedLearningPlanResponse(
                LearningPlanResponse.from(plan),
                tasks.stream().map(LearningTaskResponse::from).toList()
        );
    }
}

