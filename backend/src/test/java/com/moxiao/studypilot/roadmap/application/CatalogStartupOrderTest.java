package com.moxiao.studypilot.roadmap.application;

import com.moxiao.studypilot.course.application.CourseCatalogImporter;
import com.moxiao.studypilot.course.config.CourseCatalogConfiguration;
import com.moxiao.studypilot.roadmap.config.RoadmapCatalogConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

class CatalogStartupOrderTest {

    @Test
    void springDiscoversAndRunsCourseCatalogBeforeRoadmapCatalog() {
        CourseCatalogImporter courseImporter = mock(CourseCatalogImporter.class);
        RoadmapCatalogImporter roadmapImporter = mock(RoadmapCatalogImporter.class);
        new ApplicationContextRunner()
                .withBean(CourseCatalogImporter.class, () -> courseImporter)
                .withBean(RoadmapCatalogImporter.class, () -> roadmapImporter)
                .withUserConfiguration(
                        RoadmapCatalogConfiguration.class,
                        CourseCatalogConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(ApplicationRunner.class))
                            .containsOnlyKeys("courseCatalogRunner", "roadmapCatalogRunner");
                    List<ApplicationRunner> ordered = context
                            .getBeanProvider(ApplicationRunner.class)
                            .orderedStream()
                            .toList();
                    for (ApplicationRunner runner : ordered) {
                        runner.run(null);
                    }
                    var sequence = inOrder(courseImporter, roadmapImporter);
                    sequence.verify(courseImporter).importCatalog();
                    sequence.verify(roadmapImporter).importCatalog();
                    sequence.verifyNoMoreInteractions();
                });
    }
}
