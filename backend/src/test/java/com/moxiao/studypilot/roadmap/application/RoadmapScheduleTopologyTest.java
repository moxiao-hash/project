package com.moxiao.studypilot.roadmap.application;

import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodePrerequisiteEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RoadmapScheduleTopologyTest {
    @Test
    void prerequisiteGraphOverridesDisplayOrderDeterministically() {
        RoadmapNodeEntity dependant = node("dependant", 1);
        RoadmapNodeEntity prerequisite = node("prerequisite", 2);
        RoadmapNodePrerequisiteEntity edge = new RoadmapNodePrerequisiteEntity(
                "edge", "template", dependant.getId(), prerequisite.getId());

        List<RoadmapNodeEntity> result = RoadmapScheduleService.stableTopologicalOrder(
                List.of(dependant, prerequisite), List.of(edge), Set.of());

        assertThat(result).extracting(RoadmapNodeEntity::getId)
                .containsExactly("prerequisite", "dependant");
    }

    private RoadmapNodeEntity node(String id, int order) {
        return new RoadmapNodeEntity(
                id, "template", "stage", "module", id, order, id,
                "[]", "[]", "[]", "[]", "{}", "[]",
                30, 0, "EASY", true);
    }
}
