package com.moxiao.studypilot.roadmap.application;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RoadmapCatalogValidator {

    public record Node(String code, List<String> prerequisites) {
    }

    private enum VisitState {
        VISITING,
        VISITED
    }

    public void validate(List<Node> nodes) {
        if (nodes == null) {
            throw new IllegalArgumentException("节点列表不能为空");
        }

        Map<String, Node> nodesByCode = new HashMap<>();
        for (Node node : nodes) {
            if (node == null || node.code() == null || node.code().isBlank()) {
                throw new IllegalArgumentException("节点 code 不能为空");
            }
            if (nodesByCode.putIfAbsent(node.code(), node) != null) {
                throw new IllegalArgumentException("节点 code 重复: " + node.code());
            }
        }

        for (Node node : nodes) {
            Set<String> seenPrerequisites = new HashSet<>();
            for (String prerequisite : prerequisitesOf(node)) {
                if (prerequisite == null || prerequisite.isBlank()) {
                    throw new IllegalArgumentException("节点 " + node.code() + " 的 prerequisite 不能为空");
                }
                if (!seenPrerequisites.add(prerequisite)) {
                    throw new IllegalArgumentException("节点 " + node.code() + " 的 prerequisite 重复: " + prerequisite);
                }
                if (node.code().equals(prerequisite)) {
                    throw new IllegalArgumentException("节点不能依赖自身: " + node.code());
                }
                if (!nodesByCode.containsKey(prerequisite)) {
                    throw new IllegalArgumentException("未知 prerequisite: " + prerequisite);
                }
            }
        }

        Map<String, VisitState> states = new HashMap<>();
        for (Node node : nodes) {
            visit(node, nodesByCode, states);
        }
    }

    private void visit(Node node, Map<String, Node> nodesByCode, Map<String, VisitState> states) {
        VisitState state = states.get(node.code());
        if (state == VisitState.VISITING) {
            throw new IllegalArgumentException("依赖图存在环: " + node.code());
        }
        if (state == VisitState.VISITED) {
            return;
        }

        states.put(node.code(), VisitState.VISITING);
        for (String prerequisite : prerequisitesOf(node)) {
            visit(nodesByCode.get(prerequisite), nodesByCode, states);
        }
        states.put(node.code(), VisitState.VISITED);
    }

    private List<String> prerequisitesOf(Node node) {
        return node.prerequisites() == null ? List.of() : node.prerequisites();
    }
}
