package com.moxiao.studypilot.roadmap.application;

import com.moxiao.studypilot.course.application.CourseCatalogImporter;
import com.moxiao.studypilot.course.config.CourseCatalogConfiguration;
import com.moxiao.studypilot.roadmap.config.RoadmapCatalogConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;

import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

class CatalogStartupOrderTest {

    @Test
    void importsCourseBeforeRoadmapUsingExactRunnerOrders() throws Exception {
        CourseCatalogImporter courseImporter = mock(CourseCatalogImporter.class);
        RoadmapCatalogImporter roadmapImporter = mock(RoadmapCatalogImporter.class);
        Method courseMethod = CourseCatalogConfiguration.class.getDeclaredMethod(
                "courseCatalogRunner", CourseCatalogImporter.class);
        Method roadmapMethod = RoadmapCatalogConfiguration.class.getDeclaredMethod(
                "roadmapCatalogRunner", RoadmapCatalogImporter.class);
        courseMethod.setAccessible(true);
        roadmapMethod.setAccessible(true);

        List<OrderedRunner> runners = List.of(
                new OrderedRunner(order(courseMethod), (ApplicationRunner) courseMethod.invoke(
                        new CourseCatalogConfiguration(), courseImporter)),
                new OrderedRunner(order(roadmapMethod), (ApplicationRunner) roadmapMethod.invoke(
                        new RoadmapCatalogConfiguration(), roadmapImporter))
        ).stream().sorted(Comparator.comparingInt(OrderedRunner::order)).toList();

        assertThat(runners).extracting(OrderedRunner::order).containsExactly(10, 20);
        for (OrderedRunner runner : runners) {
            runner.runner().run(null);
        }
        var sequence = inOrder(courseImporter, roadmapImporter);
        sequence.verify(courseImporter).importCatalog();
        sequence.verify(roadmapImporter).importCatalog();
        sequence.verifyNoMoreInteractions();
    }

    private static int order(Method method) {
        Order annotation = method.getAnnotation(Order.class);
        return annotation == null ? Integer.MAX_VALUE : annotation.value();
    }

    private record OrderedRunner(int order, ApplicationRunner runner) {
    }
}
