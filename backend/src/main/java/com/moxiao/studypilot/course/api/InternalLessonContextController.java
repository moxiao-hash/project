package com.moxiao.studypilot.course.api;

import com.moxiao.studypilot.course.application.CourseLearningService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 向 Python 课内导师提供服务端裁剪后的课时上下文。
 *
 * <p>Python 不读取课程数据库，也不接收检查题答案。ownerId 只用于读取该用户
 * 的学习进度，调用方必须通过内部服务令牌。</p>
 */
@RestController
@RequestMapping("/internal/teaching/lessons")
public class InternalLessonContextController {

    private final CourseLearningService courseLearningService;

    public InternalLessonContextController(CourseLearningService courseLearningService) {
        this.courseLearningService = courseLearningService;
    }

    @GetMapping("/{lessonId}/context")
    public InternalLessonContextResponse context(
            @PathVariable String lessonId,
            @RequestParam String ownerId
    ) {
        return new InternalLessonContextResponse(
                ownerId,
                courseLearningService.getLesson(ownerId, lessonId)
        );
    }

    public record InternalLessonContextResponse(String ownerId, LessonResponse lesson) {
    }
}
