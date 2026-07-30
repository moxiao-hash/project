package com.moxiao.studypilot.course.api;

import com.moxiao.studypilot.course.domain.CoursePublicationStatus;

public record CourseSummaryResponse(
        String id,
        String slug,
        String title,
        String description,
        String techStack,
        CoursePublicationStatus publicationStatus,
        int version,
        int moduleCount,
        int lessonCount,
        int completedLessonCount,
        int progressPercent
) {
}
