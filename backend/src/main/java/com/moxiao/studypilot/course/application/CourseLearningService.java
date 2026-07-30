package com.moxiao.studypilot.course.application;

import com.moxiao.studypilot.course.api.CourseDetailResponse;
import com.moxiao.studypilot.course.api.CourseSummaryResponse;
import com.moxiao.studypilot.course.api.LessonProgressResponse;
import com.moxiao.studypilot.course.api.LessonResponse;
import com.moxiao.studypilot.course.api.LessonCheckpointResult;
import com.moxiao.studypilot.course.api.SubmitLessonCheckpointRequest;
import com.moxiao.studypilot.course.api.UpdateLessonProgressRequest;
import com.moxiao.studypilot.course.domain.CoursePublicationStatus;
import com.moxiao.studypilot.course.domain.LessonProgressStatus;
import com.moxiao.studypilot.course.infrastructure.CourseEntity;
import com.moxiao.studypilot.course.infrastructure.CourseJpaRepository;
import com.moxiao.studypilot.course.infrastructure.CourseModuleEntity;
import com.moxiao.studypilot.course.infrastructure.CourseModuleJpaRepository;
import com.moxiao.studypilot.course.infrastructure.LessonEntity;
import com.moxiao.studypilot.course.infrastructure.LessonJpaRepository;
import com.moxiao.studypilot.course.infrastructure.LessonProgressEntity;
import com.moxiao.studypilot.course.infrastructure.LessonProgressJpaRepository;
import com.moxiao.studypilot.course.infrastructure.LessonSourceEntity;
import com.moxiao.studypilot.course.infrastructure.LessonSourceJpaRepository;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CourseLearningService {

    private final CourseJpaRepository courseRepository;
    private final CourseModuleJpaRepository moduleRepository;
    private final LessonJpaRepository lessonRepository;
    private final LessonSourceJpaRepository sourceRepository;
    private final LessonProgressJpaRepository progressRepository;
    private final ObjectMapper objectMapper;

    public CourseLearningService(
            CourseJpaRepository courseRepository,
            CourseModuleJpaRepository moduleRepository,
            LessonJpaRepository lessonRepository,
            LessonSourceJpaRepository sourceRepository,
            LessonProgressJpaRepository progressRepository,
            ObjectMapper objectMapper
    ) {
        this.courseRepository = courseRepository;
        this.moduleRepository = moduleRepository;
        this.lessonRepository = lessonRepository;
        this.sourceRepository = sourceRepository;
        this.progressRepository = progressRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<CourseSummaryResponse> list(String ownerId) {
        return courseRepository.findAllByPublicationStatusOrderByCreatedAtAsc(
                        CoursePublicationStatus.PUBLISHED
                ).stream()
                .map(course -> summarize(ownerId, course))
                .toList();
    }

    @Transactional(readOnly = true)
    public CourseDetailResponse getCourse(String ownerId, String slug) {
        CourseEntity course = courseRepository.findBySlugAndPublicationStatus(
                slug,
                CoursePublicationStatus.PUBLISHED
        ).orElseThrow(() -> new ResourceNotFoundException("课程不存在"));
        List<CourseModuleEntity> modules =
                moduleRepository.findAllByCourseIdOrderByModuleOrderAsc(course.getId());
        Map<String, LessonProgressEntity> progress = progressFor(ownerId, lessonsFor(modules));
        List<CourseDetailResponse.Module> responses = modules.stream()
                .map(module -> new CourseDetailResponse.Module(
                        module.getId(),
                        module.getModuleOrder(),
                        module.getTitle(),
                        module.getDescription(),
                        lessonRepository.findAllByModuleIdOrderByLessonOrderAsc(
                                        module.getId()
                                ).stream()
                                .map(lesson -> new CourseDetailResponse.LessonItem(
                                        lesson.getId(),
                                        lesson.getSlug(),
                                        lesson.getLessonOrder(),
                                        lesson.getTitle(),
                                        lesson.getSummary(),
                                        lesson.getEstimatedMinutes(),
                                        lesson.isPublished(),
                                        progressResponse(progress.get(lesson.getId()))
                                ))
                                .toList()
                ))
                .toList();
        return new CourseDetailResponse(summarize(ownerId, course), responses);
    }

    @Transactional(readOnly = true)
    public LessonResponse getLesson(String ownerId, String lessonId) {
        LessonEntity lesson = findPublishedLesson(lessonId);
        return lessonResponse(
                lesson,
                progressRepository.findByOwnerIdAndLessonId(ownerId, lessonId).orElse(null)
        );
    }

    @Transactional
    public LessonResponse updateProgress(
            String ownerId,
            String lessonId,
            UpdateLessonProgressRequest request
    ) {
        LessonEntity lesson = findPublishedLesson(lessonId);
        Instant now = Instant.now();
        LessonProgressEntity progress = progressRepository
                .findByOwnerIdAndLessonId(ownerId, lessonId)
                .orElseGet(() -> new LessonProgressEntity(
                        UUID.randomUUID().toString(),
                        ownerId,
                        lessonId,
                        now
                ));
        progress.updateLearningActivity(
                request.videoCompleted(),
                request.readingCompleted(),
                request.lastSectionKey(),
                now
        );
        progressRepository.save(progress);
        return lessonResponse(lesson, progress);
    }

    @Transactional
    public LessonCheckpointResult submitCheckpoint(
            String ownerId,
            String lessonId,
            String blockKey,
            SubmitLessonCheckpointRequest request
    ) {
        LessonEntity lesson = findPublishedLesson(lessonId);
        tools.jackson.databind.JsonNode block = objectMapper
                .readTree(lesson.getContentJson())
                .path("blocks")
                .valueStream()
                .filter(candidate -> blockKey.equals(candidate.path("key").asText()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("课时检查题不存在"));
        if (!"CHECKPOINT".equals(block.path("type").asText())) {
            throw new IllegalArgumentException("指定内容不是检查题");
        }
        int optionCount = block.path("options").size();
        if (request.selectedOption() >= optionCount) {
            throw new IllegalArgumentException("选择项超出范围");
        }
        boolean correct = request.selectedOption() == block.path("correctOption").asInt(-1);
        LessonProgressEntity progress = progressRepository
                .findByOwnerIdAndLessonId(ownerId, lessonId)
                .orElseGet(() -> new LessonProgressEntity(
                        UUID.randomUUID().toString(),
                        ownerId,
                        lessonId,
                        Instant.now()
                ));
        if (correct) {
            progress.markCheckpointPassed(Instant.now());
            progressRepository.save(progress);
        }
        return new LessonCheckpointResult(
                correct,
                block.path("explanation").asText(),
                progressResponse(progress)
        );
    }

    @Transactional(readOnly = true)
    public LessonResponse continueLearning(String ownerId) {
        for (CourseEntity course : publishedCourses()) {
            for (CourseModuleEntity module :
                    moduleRepository.findAllByCourseIdOrderByModuleOrderAsc(course.getId())) {
                for (LessonEntity lesson :
                        lessonRepository.findAllByModuleIdAndPublishedTrueOrderByLessonOrderAsc(
                                module.getId()
                        )) {
                    LessonProgressEntity progress = progressRepository
                            .findByOwnerIdAndLessonId(ownerId, lesson.getId())
                            .orElse(null);
                    if (progress == null
                            || progress.getStatus() != LessonProgressStatus.COMPLETED) {
                        return lessonResponse(lesson, progress);
                    }
                }
            }
        }
        return null;
    }

    private List<CourseEntity> publishedCourses() {
        return courseRepository.findAllByPublicationStatusOrderByCreatedAtAsc(
                CoursePublicationStatus.PUBLISHED
        );
    }

    private CourseSummaryResponse summarize(String ownerId, CourseEntity course) {
        List<CourseModuleEntity> modules =
                moduleRepository.findAllByCourseIdOrderByModuleOrderAsc(course.getId());
        List<LessonEntity> lessons = lessonsFor(modules).stream()
                .filter(LessonEntity::isPublished)
                .toList();
        Map<String, LessonProgressEntity> progress = progressFor(ownerId, lessons);
        int completed = (int) progress.values().stream()
                .filter(item -> item.getStatus() == LessonProgressStatus.COMPLETED)
                .count();
        int percentage = lessons.isEmpty() ? 0 : completed * 100 / lessons.size();
        return new CourseSummaryResponse(
                course.getId(),
                course.getSlug(),
                course.getTitle(),
                course.getDescription(),
                course.getTechStack(),
                course.getPublicationStatus(),
                course.getVersion(),
                modules.size(),
                lessons.size(),
                completed,
                percentage
        );
    }

    private List<LessonEntity> lessonsFor(List<CourseModuleEntity> modules) {
        List<LessonEntity> lessons = new ArrayList<>();
        for (CourseModuleEntity module : modules) {
            lessons.addAll(
                    lessonRepository.findAllByModuleIdOrderByLessonOrderAsc(module.getId())
            );
        }
        return lessons;
    }

    private Map<String, LessonProgressEntity> progressFor(
            String ownerId,
            List<LessonEntity> lessons
    ) {
        if (lessons.isEmpty()) {
            return Map.of();
        }
        Map<String, LessonProgressEntity> result = new HashMap<>();
        progressRepository.findAllByOwnerIdAndLessonIdIn(
                ownerId,
                lessons.stream().map(LessonEntity::getId).toList()
        ).forEach(item -> result.put(item.getLessonId(), item));
        return result;
    }

    private LessonEntity findPublishedLesson(String lessonId) {
        return lessonRepository.findByIdAndPublishedTrue(lessonId)
                .orElseThrow(() -> new ResourceNotFoundException("课时不存在"));
    }

    private LessonResponse lessonResponse(
            LessonEntity lesson,
            LessonProgressEntity progress
    ) {
        List<LessonResponse.Source> sources = sourceRepository
                .findAllByLessonIdOrderBySourceOrderAsc(lesson.getId()).stream()
                .map(this::sourceResponse)
                .toList();
        return new LessonResponse(
                lesson.getId(),
                lesson.getModuleId(),
                lesson.getSlug(),
                lesson.getLessonOrder(),
                lesson.getTitle(),
                lesson.getSummary(),
                lesson.getEstimatedMinutes(),
                publicContent(lesson.getContentJson()),
                lesson.isPublished(),
                sources,
                progressResponse(progress)
        );
    }

    private LessonResponse.Source sourceResponse(LessonSourceEntity source) {
        return new LessonResponse.Source(
                source.getSourceType(),
                source.getTitle(),
                source.getUrl(),
                source.getLocator(),
                source.getBvid(),
                source.getVideoPage()
        );
    }

    private tools.jackson.databind.JsonNode publicContent(String contentJson) {
        tools.jackson.databind.JsonNode content = objectMapper.readTree(contentJson).deepCopy();
        content.path("blocks").forEach(block -> {
            if (block instanceof ObjectNode object) {
                object.remove("correctOption");
                object.remove("explanation");
            }
        });
        return content;
    }

    private LessonProgressResponse progressResponse(LessonProgressEntity progress) {
        return progress == null
                ? LessonProgressResponse.notStarted()
                : LessonProgressResponse.from(progress);
    }
}
