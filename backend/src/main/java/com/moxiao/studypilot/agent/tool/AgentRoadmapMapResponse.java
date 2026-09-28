package com.moxiao.studypilot.agent.tool;

import com.moxiao.studypilot.roadmap.api.RoadmapMapResponse;
import com.moxiao.studypilot.roadmap.api.RoadmapNodeResponse;
import com.moxiao.studypilot.roadmap.api.RoadmapStageResponse;

import java.util.List;

/**
 * Agent 专用路线投影：只保留 Agent 选择下一步和陈述事实所需的字段。
 *
 * <p>整条路线（v2 模板 12 阶段 / 125 节点）的完整响应包含节点目标、高频点、常见错误、
 * 检索关键词、时长、蓝图等冗长字段，序列化后远超 {@code AgentToolRegistry} 的 65536
 * 字节上限，会被替换为截断信封，导致回复编排看不到路线而误称“尚未加入学习路线”。</p>
 *
 * <p>因此这里给出**类型化**的紧凑投影，而不是提高全局上限或在序列化时静默省略：
 * 顶层保留登记/模板身份与进度计数；阶段保留身份、标题与进度；节点只保留
 * {@code id/code/title/displayStatus}（Python 的下一步选择读取 id 与 displayStatus）。
 * 公开的 {@code /api/roadmaps/current/map} 仍然返回完整 {@link RoadmapMapResponse}。</p>
 */
public record AgentRoadmapMapResponse(
        String enrollmentId,
        String roadmapCode,
        int templateVersion,
        String title,
        int completedRequiredNodes,
        int totalRequiredNodes,
        List<AgentRoadmapStage> stages
) {

    public static AgentRoadmapMapResponse from(RoadmapMapResponse map) {
        return new AgentRoadmapMapResponse(
                map.enrollmentId(),
                map.roadmapCode(),
                map.templateVersion(),
                map.title(),
                map.completedRequiredNodes(),
                map.totalRequiredNodes(),
                map.stages().stream().map(AgentRoadmapStage::from).toList());
    }

    public record AgentRoadmapStage(
            String id,
            String code,
            int order,
            String title,
            int completedRequiredNodes,
            int totalRequiredNodes,
            List<AgentRoadmapNode> nodes
    ) {
        static AgentRoadmapStage from(RoadmapStageResponse stage) {
            return new AgentRoadmapStage(
                    stage.id(),
                    stage.code(),
                    stage.order(),
                    stage.title(),
                    stage.completedRequiredNodes(),
                    stage.totalRequiredNodes(),
                    stage.nodes().stream().map(AgentRoadmapNode::from).toList());
        }
    }

    /** 节点身份、标题与显示状态：足够选择下一步，且不含任何冗长内容。 */
    public record AgentRoadmapNode(
            String id,
            String code,
            String title,
            String displayStatus
    ) {
        static AgentRoadmapNode from(RoadmapNodeResponse node) {
            return new AgentRoadmapNode(
                    node.id(), node.code(), node.title(), node.displayStatus());
        }
    }
}
