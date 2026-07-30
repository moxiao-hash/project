package com.moxiao.studypilot.course.api;

import com.moxiao.studypilot.course.domain.LessonSourceType;
import tools.jackson.databind.JsonNode;

import java.util.List;

public record LessonResponse(
        String id,
        String moduleId,
        String slug,
        int order,
        String title,
        String summary,
        int estimatedMinutes,
        JsonNode content,
        boolean published,
        List<Source> sources,
        LessonProgressResponse progress
) {
    public record Source(
            LessonSourceType type,
            String title,
            String url,
            String locator,
            String bvid,
            Integer videoPage
    ) {
    }
}
