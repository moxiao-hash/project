package com.moxiao.studypilot.roadmap.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RoadmapDiagnosticResultMigrationTest {

    @Test
    void backfillsLongTextBeforeMakingTheColumnRequiredWithoutUsingATextDefault()
            throws IOException {
        String migration = read("db/migration/V34__record_roadmap_diagnostic_results.sql");

        int addNullable = migration.indexOf(
                "ADD COLUMN mastered_node_ids_json LONGTEXT NULL"
        );
        int backfill = migration.indexOf(
                "UPDATE roadmap_diagnostics"
        );
        int makeRequired = migration.indexOf(
                "MODIFY COLUMN mastered_node_ids_json LONGTEXT NOT NULL"
        );

        assertThat(addNullable).isGreaterThanOrEqualTo(0).isLessThan(backfill);
        assertThat(makeRequired).isGreaterThan(backfill);
        assertThat(migration).doesNotContain("LONGTEXT NOT NULL DEFAULT");
    }

    private String read(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
