package com.moxiao.studypilot.roadmap.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RoadmapQuizBindingMigrationTest {

    @Test
    void replacesTheOldOriginCheckBeforeBackfillAndSelectsOneDeterministicJob()
            throws IOException {
        String migration = read("db/migration/V29__bind_node_quizzes_to_roadmaps.sql");

        int drop = migration.indexOf("DROP CONSTRAINT ck_quizzes_purpose_origin");
        int update = migration.indexOf("UPDATE quizzes");
        int recreate = migration.lastIndexOf("ADD CONSTRAINT ck_quizzes_purpose_origin");

        assertThat(drop).isGreaterThanOrEqualTo(0).isLessThan(update);
        assertThat(recreate).isGreaterThan(update);
        assertThat(migration).contains("ORDER BY jobs.created_at DESC LIMIT 1");
        assertThat(migration).contains("user_roadmap_node_id IS NOT NULL");
    }

    private String read(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
