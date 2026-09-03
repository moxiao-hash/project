package com.moxiao.studypilot.assessment.application;

import com.moxiao.studypilot.assessment.infrastructure.QuizEntity;
import com.moxiao.studypilot.course.infrastructure.CourseModuleJpaRepository;
import com.moxiao.studypilot.course.infrastructure.LessonJpaRepository;
import com.moxiao.studypilot.learning.infrastructure.LearningTaskJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapModuleJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeJpaRepository;
import org.springframework.stereotype.Component;

@Component
public class WrongQuestionChapterResolver {
    private final RoadmapNodeJpaRepository nodeRepository;
    private final RoadmapModuleJpaRepository roadmapModuleRepository;
    private final LessonJpaRepository lessonRepository;
    private final CourseModuleJpaRepository courseModuleRepository;
    private final LearningTaskJpaRepository taskRepository;

    public WrongQuestionChapterResolver(
            RoadmapNodeJpaRepository nodeRepository,
            RoadmapModuleJpaRepository roadmapModuleRepository,
            LessonJpaRepository lessonRepository,
            CourseModuleJpaRepository courseModuleRepository,
            LearningTaskJpaRepository taskRepository
    ) {
        this.nodeRepository = nodeRepository;
        this.roadmapModuleRepository = roadmapModuleRepository;
        this.lessonRepository = lessonRepository;
        this.courseModuleRepository = courseModuleRepository;
        this.taskRepository = taskRepository;
    }

    public Chapter resolve(QuizEntity quiz) {
        if (quiz.getRoadmapNodeId() != null && quiz.getRoadmapTemplateId() != null) {
            var node = nodeRepository.findByIdAndTemplateId(
                    quiz.getRoadmapNodeId(), quiz.getRoadmapTemplateId()).orElse(null);
            if (node != null && node.getModuleId() != null) {
                var module = roadmapModuleRepository.findByIdAndTemplateId(
                        node.getModuleId(), quiz.getRoadmapTemplateId()).orElse(null);
                if (module != null) {
                    return new Chapter("roadmap-module:" + module.getId(), module.getTitle());
                }
            }
        }
        if (quiz.getLessonId() != null && lessonRepository != null
                && courseModuleRepository != null) {
            var lesson = lessonRepository.findById(quiz.getLessonId()).orElse(null);
            if (lesson != null) {
                var module = courseModuleRepository.findById(lesson.getModuleId()).orElse(null);
                if (module != null) {
                    return new Chapter("course-module:" + module.getId(), module.getTitle());
                }
                return new Chapter("lesson:" + lesson.getId(), lesson.getTitle());
            }
        }
        if (quiz.getTaskId() != null && taskRepository != null) {
            var task = taskRepository.findByIdAndOwnerId(
                    quiz.getTaskId(), quiz.getOwnerId()).orElse(null);
            if (task != null) return new Chapter("task:" + task.getId(), task.getTitle());
        }
        return new Chapter("quiz:" + quiz.getId(), quiz.getTitle());
    }

    public record Chapter(String key, String title) {
    }
}
