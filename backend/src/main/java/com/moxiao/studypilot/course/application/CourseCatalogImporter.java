package com.moxiao.studypilot.course.application;

import com.moxiao.studypilot.course.domain.CoursePublicationStatus;
import com.moxiao.studypilot.course.domain.LessonSourceType;
import com.moxiao.studypilot.course.infrastructure.CourseEntity;
import com.moxiao.studypilot.course.infrastructure.CourseJpaRepository;
import com.moxiao.studypilot.course.infrastructure.CourseModuleEntity;
import com.moxiao.studypilot.course.infrastructure.CourseModuleJpaRepository;
import com.moxiao.studypilot.course.infrastructure.LessonEntity;
import com.moxiao.studypilot.course.infrastructure.LessonJpaRepository;
import com.moxiao.studypilot.course.infrastructure.LessonSourceEntity;
import com.moxiao.studypilot.course.infrastructure.LessonSourceJpaRepository;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class CourseCatalogImporter {

    private static final String CATALOG_PATH =
            "courses/studypilot-java-ai-v1.json";
    private static final Pattern BVID = Pattern.compile("BV[0-9A-Za-z]{10}");
    private static final Set<String> VIDEO_HOSTS = Set.of(
            "bilibili.com",
            "www.bilibili.com"
    );

    private final CourseJpaRepository courseRepository;
    private final CourseModuleJpaRepository moduleRepository;
    private final LessonJpaRepository lessonRepository;
    private final LessonSourceJpaRepository sourceRepository;
    private final ObjectMapper objectMapper;

    public CourseCatalogImporter(
            CourseJpaRepository courseRepository,
            CourseModuleJpaRepository moduleRepository,
            LessonJpaRepository lessonRepository,
            LessonSourceJpaRepository sourceRepository,
            ObjectMapper objectMapper
    ) {
        this.courseRepository = courseRepository;
        this.moduleRepository = moduleRepository;
        this.lessonRepository = lessonRepository;
        this.sourceRepository = sourceRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void importCatalog() {
        Catalog catalog = readCatalog();
        Instant now = Instant.now();
        courseRepository.save(new CourseEntity(
                catalog.id(),
                catalog.slug(),
                catalog.title(),
                catalog.description(),
                catalog.techStack(),
                CoursePublicationStatus.PUBLISHED,
                catalog.version(),
                now
        ));
        for (Module module : catalog.modules()) {
            moduleRepository.save(new CourseModuleEntity(
                    module.id(),
                    catalog.id(),
                    module.order(),
                    module.title(),
                    module.description()
            ));
            for (Lesson lesson : module.lessons()) {
                lessonRepository.save(new LessonEntity(
                        lesson.id(),
                        module.id(),
                        lesson.order(),
                        lesson.slug(),
                        lesson.title(),
                        lesson.summary(),
                        lesson.estimatedMinutes(),
                        writeContent(lesson.blocks()),
                        lesson.published()
                ));
                int sourceOrder = 1;
                for (Source source : lesson.sources()) {
                    if (source.type() == LessonSourceType.VIDEO) {
                        validateVideo(source.url(), source.bvid(), source.videoPage());
                    }
                    sourceRepository.save(new LessonSourceEntity(
                            source.id(),
                            lesson.id(),
                            sourceOrder++,
                            source.type(),
                            source.title(),
                            source.url(),
                            source.locator(),
                            source.bvid(),
                            source.videoPage()
                    ));
                }
            }
        }
    }

    void validateVideo(String url, String bvid, Integer videoPage) {
        URI uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !VIDEO_HOSTS.contains(uri.getHost())
                || bvid == null
                || !BVID.matcher(bvid).matches()
                || videoPage == null
                || videoPage < 1
                || !uri.getPath().contains(bvid)) {
            throw new IllegalArgumentException("无效的 B 站课程来源");
        }
    }

    private Catalog readCatalog() {
        try (var input = new ClassPathResource(CATALOG_PATH).getInputStream()) {
            return objectMapper.readValue(input, Catalog.class);
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取内置课程目录", exception);
        }
    }

    private String writeContent(List<JsonNode> blocks) {
        try {
            return objectMapper.writeValueAsString(new LessonContent(blocks));
        } catch (Exception exception) {
            throw new IllegalStateException("无法序列化内置课时内容", exception);
        }
    }

    private record Catalog(
            String id,
            String slug,
            String title,
            String description,
            String techStack,
            int version,
            List<Module> modules
    ) {
    }

    private record Module(
            String id,
            int order,
            String title,
            String description,
            List<Lesson> lessons
    ) {
    }

    private record Lesson(
            String id,
            String slug,
            int order,
            String title,
            String summary,
            int estimatedMinutes,
            boolean published,
            List<JsonNode> blocks,
            List<Source> sources
    ) {
    }

    private record Source(
            String id,
            LessonSourceType type,
            String title,
            String url,
            String locator,
            String bvid,
            Integer videoPage
    ) {
    }

    private record LessonContent(List<JsonNode> blocks) {
    }
}
