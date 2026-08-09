package com.moxiao.studypilot.roadmap.infrastructure;

import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class RoadmapEntityMappingTest {

    @Test
    void mapsRoadmapEntitiesToDedicatedTables() {
        assertTableName(RoadmapTemplateEntity.class, "roadmap_templates");
        assertTableName(RoadmapStageEntity.class, "roadmap_stages");
        assertTableName(RoadmapNodeEntity.class, "roadmap_nodes");
        assertTableName(UserRoadmapEntity.class, "user_roadmaps");
        assertTableName(UserRoadmapNodeEntity.class, "user_roadmap_nodes");
    }

    @Test
    void userRoadmapNodeUsesOptimisticLocking() {
        boolean hasVersionField = Arrays.stream(UserRoadmapNodeEntity.class.getDeclaredFields())
                .map(Field::getDeclaredAnnotations)
                .flatMap(Arrays::stream)
                .anyMatch(annotation -> annotation.annotationType().equals(Version.class));

        assertThat(hasVersionField).isTrue();
    }

    private void assertTableName(Class<?> entityType, String tableName) {
        assertThat(entityType.getAnnotation(Table.class))
                .isNotNull()
                .extracting(Table::name)
                .isEqualTo(tableName);
    }
}
