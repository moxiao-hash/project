package com.moxiao.studypilot.course.api;

public record LessonCheckpointResult(
        boolean correct,
        String explanation,
        LessonProgressResponse progress
) {
}
