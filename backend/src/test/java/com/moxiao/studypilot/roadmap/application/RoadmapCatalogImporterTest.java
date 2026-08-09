package com.moxiao.studypilot.roadmap.application;

import com.moxiao.studypilot.roadmap.domain.RoadmapPublicationStatus;
import com.moxiao.studypilot.course.application.CourseCatalogImporter;
import com.moxiao.studypilot.course.infrastructure.LessonJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.LegacyLessonRoadmapMappingJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodePrerequisiteJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapStageJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapTemplateEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapTemplateJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class RoadmapCatalogImporterTest {

    @Autowired RoadmapCatalogImporter importer;
    @Autowired CourseCatalogImporter courseCatalogImporter;
    @Autowired LessonJpaRepository lessonRepository;
    @Autowired RoadmapTemplateJpaRepository templateRepository;
    @Autowired RoadmapStageJpaRepository stageRepository;
    @Autowired RoadmapNodeJpaRepository nodeRepository;
    @Autowired RoadmapNodePrerequisiteJpaRepository prerequisiteRepository;
    @Autowired LegacyLessonRoadmapMappingJpaRepository legacyMappingRepository;

    @BeforeEach
    void cleanCatalog() {
        legacyMappingRepository.deleteAll();
        prerequisiteRepository.deleteAll();
        nodeRepository.deleteAll();
        stageRepository.deleteAll();
        templateRepository.deleteAll();
        courseCatalogImporter.importCatalog();
    }

    @Test
    void importsVersionedCatalogIdempotently() {
        importer.importCatalog();

        long templatesAfterFirstImport = templateRepository.count();
        long stagesAfterFirstImport = stageRepository.count();
        long nodesAfterFirstImport = nodeRepository.count();
        long prerequisitesAfterFirstImport = prerequisiteRepository.count();

        importer.importCatalog();

        assertThat(templateRepository.count()).isEqualTo(templatesAfterFirstImport).isEqualTo(1);
        assertThat(stageRepository.count()).isEqualTo(stagesAfterFirstImport).isEqualTo(12);
        assertThat(nodeRepository.count()).isEqualTo(nodesAfterFirstImport).isEqualTo(64);
        assertThat(prerequisiteRepository.count()).isEqualTo(prerequisitesAfterFirstImport).isEqualTo(79);
        assertThat(legacyMappingRepository.count()).isEqualTo(1);
        assertThat(legacyMappingRepository.findByLessonIdAndTemplateId(
                "lesson-rest-controller", "studypilot-java-ai-v1"))
                .get().satisfies(mapping -> assertThat(mapping.getNodeId())
                        .isEqualTo("studypilot-java-ai-v1-spring-mvc-rest"));

        RoadmapTemplateEntity template = templateRepository
                .findByRoadmapCodeAndTemplateVersion("studypilot-java-ai", 1)
                .orElseThrow();
        assertThat(template.getPublicationStatus()).isEqualTo(RoadmapPublicationStatus.PUBLISHED);
        assertThat(template.getTitle()).isEqualTo("StudyPilot Java + AI 学习路线");
    }

    @Test
    void rejectsMissingLegacyLessonWithoutChangingPublishedTemplate() {
        courseCatalogImporter.importCatalog();
        lessonRepository.deleteById("lesson-rest-controller");

        assertThatThrownBy(() -> importer.importCatalog())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("旧课时不存在: lesson-rest-controller");

        assertThat(templateRepository.count()).isZero();
        assertThat(legacyMappingRepository.count()).isZero();
    }

    @Test
    void rejectsMissingMappedNodeWithoutMutatingImmutableTemplate() {
        importer.importCatalog();
        RoadmapTemplateEntity before = templateRepository.findById("studypilot-java-ai-v1").orElseThrow();
        String checksum = before.getContentChecksum();
        legacyMappingRepository.deleteAll();
        nodeRepository.deleteById("studypilot-java-ai-v1-spring-mvc-rest");

        assertThatThrownBy(() -> importer.importCatalog())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("路线节点不存在: spring-mvc-rest");

        RoadmapTemplateEntity after = templateRepository.findById("studypilot-java-ai-v1").orElseThrow();
        assertThat(after.getContentChecksum()).isEqualTo(checksum);
        assertThat(after.getTitle()).isEqualTo(before.getTitle());
        assertThat(legacyMappingRepository.count()).isZero();
    }

    @Test
    void refusesToModifyPublishedVersionWhenChecksumDiffers() {
        RoadmapTemplateEntity existing = new RoadmapTemplateEntity(
                "studypilot-java-ai-v1",
                "studypilot-java-ai",
                1,
                "既有标题",
                "既有描述",
                RoadmapPublicationStatus.PUBLISHED,
                "0".repeat(64),
                Instant.now()
        );
        templateRepository.saveAndFlush(existing);

        assertThatThrownBy(() -> importer.importCatalog())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("已发布路线版本不可修改");

        assertThat(templateRepository.count()).isEqualTo(1);
        assertThat(stageRepository.count()).isZero();
        assertThat(nodeRepository.count()).isZero();
        assertThat(prerequisiteRepository.count()).isZero();
        assertThat(templateRepository.findById(existing.getId()).orElseThrow().getTitle())
                .isEqualTo("既有标题");
    }
}
