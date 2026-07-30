package com.moxiao.studypilot.learning.api;

import com.moxiao.studypilot.assessment.api.MasteryResponse;
import com.moxiao.studypilot.material.api.MaterialResponse;

import java.util.List;

public record InternalLearningContextResponse(
        String timeZone,
        List<LearningGoalResponse> goals,
        List<LearningPlanResponse> plans,
        List<LearningTaskResponse> tasks,
        List<MaterialResponse> materials,
        List<MasteryResponse> mastery
) {
}
