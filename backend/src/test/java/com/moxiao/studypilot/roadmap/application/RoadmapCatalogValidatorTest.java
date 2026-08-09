package com.moxiao.studypilot.roadmap.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoadmapCatalogValidatorTest {

    private final RoadmapCatalogValidator validator = new RoadmapCatalogValidator();

    @Test
    void rejectsUnknownPrerequisite() {
        assertThatThrownBy(() -> validator.validate(List.of(
                new RoadmapCatalogValidator.Node("java-oop", List.of("missing"))
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void rejectsCycle() {
        assertThatThrownBy(() -> validator.validate(List.of(
                new RoadmapCatalogValidator.Node("a", List.of("b")),
                new RoadmapCatalogValidator.Node("b", List.of("a"))
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("环");
    }

    @Test
    void rejectsDuplicateCode() {
        assertThatThrownBy(() -> validator.validate(List.of(
                new RoadmapCatalogValidator.Node("same-code", List.of()),
                new RoadmapCatalogValidator.Node("same-code", List.of())
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same-code");
    }
}
