package com.moxiao.studypilot.course.api;

import com.moxiao.studypilot.auth.security.AuthenticatedUser;
import com.moxiao.studypilot.course.application.CourseLearningService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class CourseController {

    private final CourseLearningService service;

    public CourseController(CourseLearningService service) {
        this.service = service;
    }

    @GetMapping("/courses")
    public List<CourseSummaryResponse> list(
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return service.list(user.id());
    }

    @GetMapping("/courses/continue")
    public ResponseEntity<LessonResponse> continueLearning(
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        LessonResponse lesson = service.continueLearning(user.id());
        return lesson == null
                ? ResponseEntity.noContent().build()
                : ResponseEntity.ok(lesson);
    }

    @GetMapping("/courses/{courseSlug}")
    public CourseDetailResponse getCourse(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String courseSlug
    ) {
        return service.getCourse(user.id(), courseSlug);
    }

    @GetMapping("/lessons/{lessonId}")
    public LessonResponse getLesson(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String lessonId
    ) {
        return service.getLesson(user.id(), lessonId);
    }

    @PutMapping("/lessons/{lessonId}/progress")
    public LessonResponse updateProgress(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String lessonId,
            @Valid @RequestBody UpdateLessonProgressRequest request
    ) {
        return service.updateProgress(user.id(), lessonId, request);
    }

    @PostMapping("/lessons/{lessonId}/checkpoints/{blockKey}/attempts")
    public LessonCheckpointResult submitCheckpoint(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String lessonId,
            @PathVariable String blockKey,
            @Valid @RequestBody SubmitLessonCheckpointRequest request
    ) {
        return service.submitCheckpoint(user.id(), lessonId, blockKey, request);
    }
}
