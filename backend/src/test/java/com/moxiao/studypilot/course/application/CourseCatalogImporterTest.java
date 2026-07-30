package com.moxiao.studypilot.course.application;

import com.moxiao.studypilot.course.infrastructure.CourseJpaRepository;
import com.moxiao.studypilot.course.infrastructure.CourseModuleJpaRepository;
import com.moxiao.studypilot.course.infrastructure.LessonJpaRepository;
import com.moxiao.studypilot.course.infrastructure.LessonSourceJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class CourseCatalogImporterTest {

    @Autowired
    private CourseCatalogImporter importer;

    @Autowired
    private CourseLearningService learningService;

    @Autowired
    private CourseJpaRepository courseRepository;

    @Autowired
    private CourseModuleJpaRepository moduleRepository;

    @Autowired
    private LessonJpaRepository lessonRepository;

    @Autowired
    private LessonSourceJpaRepository sourceRepository;

    @Test
    void importsTheNineStageRoadmapIdempotently() {
        importer.importCatalog();
        importer.importCatalog();

        assertThat(courseRepository.count()).isEqualTo(1);
        assertThat(moduleRepository.count()).isEqualTo(9);
        assertThat(lessonRepository.count()).isEqualTo(9);
        assertThat(sourceRepository.count()).isGreaterThanOrEqualTo(12);
    }

    @Test
    void hidesCheckpointAnswersFromThePublicLessonResponse() {
        importer.importCatalog();

        var lesson = learningService.getLesson("owner-1", "lesson-rest-controller");

        assertThat(lesson.content().toString()).contains("为什么注册接口接收");
        assertThat(lesson.content().toString()).doesNotContain("correctOption");
        assertThat(lesson.content().toString()).doesNotContain("DTO 明确公共契约");
    }

    @Test
    void rejectsNonBilibiliVideoHosts() {
        assertThatThrownBy(() -> importer.validateVideo(
                "https://example.com/video/BV14z4y1N7pg",
                "BV14z4y1N7pg",
                15
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
