package com.moxiao.studypilot.roadmap.application;

import com.moxiao.studypilot.roadmap.domain.AvailabilityStatus;
import com.moxiao.studypilot.roadmap.domain.RoadmapDisplayStatus;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodePrerequisiteEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodePrerequisiteJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapStageEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapStageJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapTemplateJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapEntity;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapNodeEntity;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapNodeJpaRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RoadmapQueryServiceTest {

    @Test
    void nodeDetailUsesOnlyTargetScopedRepositoryCalls() {
        Fixture fixture = new Fixture();
        RoadmapNodeEntity requested = fixture.node("node", "stage", "node-code", "[]");
        RoadmapNodeEntity prerequisite = fixture.node("prerequisite", "earlier", "prerequisite-code", "[]");
        UserRoadmapNodeEntity state = fixture.state("node");
        when(fixture.nodes.findByIdAndTemplateId("node", "template"))
                .thenReturn(Optional.of(requested));
        when(fixture.states.findByUserRoadmapIdAndNodeId("enrollment", "node"))
                .thenReturn(Optional.of(state));
        when(fixture.prerequisites.findAllByTemplateIdAndNodeId("template", "node"))
                .thenReturn(List.of(new RoadmapNodePrerequisiteEntity(
                        "edge", "template", "node", "prerequisite")));
        when(fixture.nodes.findAllByTemplateIdAndIdIn("template", List.of("prerequisite")))
                .thenReturn(List.of(prerequisite));

        assertThat(fixture.service.currentNode("owner", "node").prerequisiteCodes())
                .containsExactly("prerequisite-code");

        fixture.verifyCurrentEnrollment();
        verify(fixture.nodes).findByIdAndTemplateId("node", "template");
        verify(fixture.states).findByUserRoadmapIdAndNodeId("enrollment", "node");
        verify(fixture.prerequisites).findAllByTemplateIdAndNodeId("template", "node");
        verify(fixture.nodes).findAllByTemplateIdAndIdIn("template", List.of("prerequisite"));
        verify(fixture.nodes, never())
                .findAllByTemplateIdOrderByStageIdAscNodeOrderAsc("template");
        verify(fixture.prerequisites, never()).findAllByTemplateId("template");
        verify(fixture.states, never()).findAllByUserRoadmapId("enrollment");
        verifyNoInteractions(fixture.templates, fixture.stages);
        verifyNoMoreInteractions(
                fixture.userRoadmaps, fixture.nodes, fixture.prerequisites, fixture.states);
    }

    @Test
    void stageDetailUsesOnlyStageScopedRepositoryCalls() {
        Fixture fixture = new Fixture();
        RoadmapStageEntity requested = new RoadmapStageEntity(
                "stage", "template", "stage-code", 1, "阶段", "描述", "项目");
        RoadmapNodeEntity node = fixture.node("node", "stage", "node-code", "[]");
        UserRoadmapNodeEntity state = fixture.state("node");
        when(fixture.stages.findByIdAndTemplateId("stage", "template"))
                .thenReturn(Optional.of(requested));
        when(fixture.nodes.findAllByStageIdAndTemplateIdOrderByNodeOrderAsc("stage", "template"))
                .thenReturn(List.of(node));
        when(fixture.states.findAllByUserRoadmapIdAndNodeIdIn("enrollment", List.of("node")))
                .thenReturn(List.of(state));
        when(fixture.prerequisites.findAllByTemplateIdAndNodeIdIn("template", List.of("node")))
                .thenReturn(List.of());

        assertThat(fixture.service.currentStage("owner", "stage").nodes())
                .extracting(response -> response.code())
                .containsExactly("node-code");

        fixture.verifyCurrentEnrollment();
        verify(fixture.stages).findByIdAndTemplateId("stage", "template");
        verify(fixture.nodes)
                .findAllByStageIdAndTemplateIdOrderByNodeOrderAsc("stage", "template");
        verify(fixture.states)
                .findAllByUserRoadmapIdAndNodeIdIn("enrollment", List.of("node"));
        verify(fixture.prerequisites)
                .findAllByTemplateIdAndNodeIdIn("template", List.of("node"));
        verify(fixture.nodes, never())
                .findAllByTemplateIdOrderByStageIdAscNodeOrderAsc("template");
        verify(fixture.prerequisites, never()).findAllByTemplateId("template");
        verify(fixture.states, never()).findAllByUserRoadmapId("enrollment");
        verifyNoInteractions(fixture.templates);
        verifyNoMoreInteractions(
                fixture.userRoadmaps, fixture.stages, fixture.nodes,
                fixture.prerequisites, fixture.states);
    }

    @Test
    void reportsRequestedNodeCorruptionWithDeterministicContext() {
        Fixture malformed = new Fixture();
        malformed.stubRequestedNode(malformed.node("node", "stage", "node-code", "{not-json"),
                Optional.of(malformed.state("node")));
        assertThatThrownBy(() -> malformed.service.currentNode("owner", "node"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("路线节点元数据无效: node/objectives");

        Fixture missingState = new Fixture();
        missingState.stubRequestedNode(
                missingState.node("node", "stage", "node-code", "[]"), Optional.empty());
        assertThatThrownBy(() -> missingState.service.currentNode("owner", "node"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("用户路线节点状态不存在: node");
    }

    @Test
    void reportsAnInconsistentRequestedPrerequisiteWithDeterministicContext() {
        Fixture fixture = new Fixture();
        fixture.stubRequestedNode(
                fixture.node("node", "stage", "node-code", "[]"), Optional.of(fixture.state("node")));
        when(fixture.prerequisites.findAllByTemplateIdAndNodeId("template", "node"))
                .thenReturn(List.of(new RoadmapNodePrerequisiteEntity(
                        "edge", "template", "node", "missing")));
        when(fixture.nodes.findAllByTemplateIdAndIdIn("template", List.of("missing")))
                .thenReturn(List.of());

        assertThatThrownBy(() -> fixture.service.currentNode("owner", "node"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("路线前置节点不存在: missing");
    }

    @Test
    void definesEveryDisplayStatusAsAnExplicitApiValue() {
        assertThat(RoadmapDisplayStatus.values()).extracting(Enum::name).containsExactly(
                "COMPLETED", "LOCKED", "REVIEW_REQUIRED", "QUIZ_PENDING",
                "AVAILABLE", "SCHEDULED", "IN_PROGRESS");
    }

    private static final class Fixture {
        private final UserRoadmapJpaRepository userRoadmaps = mock(UserRoadmapJpaRepository.class);
        private final RoadmapTemplateJpaRepository templates = mock(RoadmapTemplateJpaRepository.class);
        private final RoadmapStageJpaRepository stages = mock(RoadmapStageJpaRepository.class);
        private final RoadmapNodeJpaRepository nodes = mock(RoadmapNodeJpaRepository.class);
        private final RoadmapNodePrerequisiteJpaRepository prerequisites =
                mock(RoadmapNodePrerequisiteJpaRepository.class);
        private final UserRoadmapNodeJpaRepository states = mock(UserRoadmapNodeJpaRepository.class);
        private final Instant now = Instant.now();
        private final RoadmapQueryService service = new RoadmapQueryService(
                userRoadmaps, templates, stages, nodes, prerequisites, states, new ObjectMapper());

        private Fixture() {
            when(userRoadmaps.findByOwnerIdAndActiveSlot("owner", "CURRENT"))
                    .thenReturn(Optional.of(new UserRoadmapEntity(
                            "enrollment", "owner", "template", now)));
        }

        private RoadmapNodeEntity node(
                String id,
                String stageId,
                String code,
                String objectives
        ) {
            return new RoadmapNodeEntity(
                    id, "template", stageId, code, 1, "节点", objectives,
                    "[]", "[]", "[]", "{\"required\":false}", "[]",
                    30, 15, "EASY", true);
        }

        private UserRoadmapNodeEntity state(String nodeId) {
            return new UserRoadmapNodeEntity(
                    "state-" + nodeId, "enrollment", nodeId, "owner", "template",
                    AvailabilityStatus.AVAILABLE, false, now);
        }

        private void stubRequestedNode(
                RoadmapNodeEntity node,
                Optional<UserRoadmapNodeEntity> state
        ) {
            when(nodes.findByIdAndTemplateId(node.getId(), "template"))
                    .thenReturn(Optional.of(node));
            when(states.findByUserRoadmapIdAndNodeId("enrollment", node.getId()))
                    .thenReturn(state);
            when(prerequisites.findAllByTemplateIdAndNodeId("template", node.getId()))
                    .thenReturn(List.of());
        }

        private void verifyCurrentEnrollment() {
            verify(userRoadmaps).findByOwnerIdAndActiveSlot("owner", "CURRENT");
        }
    }
}
