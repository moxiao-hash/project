package com.moxiao.studypilot.roadmap.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
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

    @Test
    void acceptsValidDag() {
        assertThatCode(() -> validator.validate(List.of(
                new RoadmapCatalogValidator.Node("foundation", List.of()),
                new RoadmapCatalogValidator.Node("backend", List.of("foundation")),
                new RoadmapCatalogValidator.Node("agent", List.of("foundation", "backend"))
        ))).doesNotThrowAnyException();
    }

    @Test
    void rejectsDuplicatePrerequisite() {
        assertThatThrownBy(() -> validator.validate(List.of(
                new RoadmapCatalogValidator.Node("foundation", List.of()),
                new RoadmapCatalogValidator.Node("backend", List.of("foundation", "foundation"))
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prerequisite 重复")
                .hasMessageContaining("foundation");
    }

    @Test
    void rejectsSelfReference() {
        assertThatThrownBy(() -> validator.validate(List.of(
                new RoadmapCatalogValidator.Node("self", List.of("self"))
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("依赖自身")
                .hasMessageContaining("self");
    }

    @Test
    void rejectsBlankPrerequisite() {
        assertThatThrownBy(() -> validator.validate(List.of(
                new RoadmapCatalogValidator.Node("node", List.of(" "))
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prerequisite 不能为空")
                .hasMessageContaining("node");
    }

    @Test
    void rejectsBlankCode() {
        assertThatThrownBy(() -> validator.validate(List.of(
                new RoadmapCatalogValidator.Node(" ", List.of())
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("code 不能为空");
    }
}
