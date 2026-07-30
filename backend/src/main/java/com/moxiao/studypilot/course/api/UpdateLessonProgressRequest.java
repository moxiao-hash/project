package com.moxiao.studypilot.course.api;

import jakarta.validation.constraints.Size;

public record UpdateLessonProgressRequest(
        boolean videoCompleted,
        boolean readingCompleted,
        @Size(max = 120) String lastSectionKey
) {
}
