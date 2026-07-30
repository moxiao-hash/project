package com.moxiao.studypilot.course.api;

import jakarta.validation.constraints.Min;

public record SubmitLessonCheckpointRequest(
        @Min(0) int selectedOption
) {
}
