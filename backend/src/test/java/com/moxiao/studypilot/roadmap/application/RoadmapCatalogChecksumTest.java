package com.moxiao.studypilot.roadmap.application;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class RoadmapCatalogChecksumTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void ignoresObjectKeyOrderAndInsignificantWhitespace() {
        var first = objectMapper.readTree("{\"b\": 2, \"a\": {\"y\": true, \"x\": 1}}");
        var reordered = objectMapper.readTree("""
                {
                  "a": { "x": 1, "y": true },
                  "b": 2
                }
                """);

        assertThat(RoadmapCatalogChecksum.sha256(objectMapper, first))
                .isEqualTo(RoadmapCatalogChecksum.sha256(objectMapper, reordered));
    }

    @Test
    void preservesArrayOrderInChecksum() {
        var first = objectMapper.readTree("{\"items\":[\"a\",\"b\"]}");
        var reordered = objectMapper.readTree("{\"items\":[\"b\",\"a\"]}");

        assertThat(RoadmapCatalogChecksum.sha256(objectMapper, first))
                .isNotEqualTo(RoadmapCatalogChecksum.sha256(objectMapper, reordered));
    }
}
