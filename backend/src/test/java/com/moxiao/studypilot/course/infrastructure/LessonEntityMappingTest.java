package com.moxiao.studypilot.course.infrastructure;

import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LessonEntityMappingTest {

    @Test
    void contentJsonUsesTheSameLongTextTypeAsTheFlywaySchema() throws Exception {
        Column column = LessonEntity.class
                .getDeclaredField("contentJson")
                .getAnnotation(Column.class);

        assertThat(column.columnDefinition()).isEqualTo("LONGTEXT");
    }
}
