package com.moxiao.studypilot.course.api;

import java.util.List;

public record CourseDetailResponse(
        CourseSummaryResponse course,
        List<Module> modules
) {
    public record Module(
            String id,
            int order,
            String title,
            String description,
            List<LessonItem> lessons
    ) {
    }

    public record LessonItem(
            String id,
            String slug,
            int order,
            String title,
            String summary,
            int estimatedMinutes,
            boolean published,
            LessonProgressResponse progress
    ) {
    }
}
