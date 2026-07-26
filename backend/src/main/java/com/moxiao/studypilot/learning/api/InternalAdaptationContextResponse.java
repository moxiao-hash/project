package com.moxiao.studypilot.learning.api;

import java.time.LocalDate;
import java.util.List;

public record InternalAdaptationContextResponse(
        String ownerId,
        LocalDate analysisDate,
        int windowDays,
        int dailyStudyLimitMinutes,
        LearningPlanResponse plan,
        List<LearningTaskResponse> tasks,
        List<AdaptationSignalResponse> signals
) {
}
