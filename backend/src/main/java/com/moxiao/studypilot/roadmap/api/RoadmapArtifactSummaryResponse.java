package com.moxiao.studypilot.roadmap.api;

import com.moxiao.studypilot.roadmap.domain.ArtifactEvaluationMode;
import com.moxiao.studypilot.roadmap.domain.ArtifactStatus;

import java.time.Instant;
import java.util.List;

/**
 * Task 33 §8：`/workspaces` 只读实践成果结果面板的最小投影。
 *
 * <p>该面板只展示“成果状态、所属节点与适合展示的评测信息”。因此本 DTO **刻意**不包含
 * 绝对/相对文件路径、成果内容描述、测试证据、敏感扫描明细与原始评审载荷
 * （{@code rubricBreakdownJson}、{@code details}），避免把本机路径或私有内容带到
 * 浏览器结果面板这一面上。</p>
 *
 * <p>它只用于 {@code GET /api/roadmap-artifacts}；显式的单项
 * {@code GET /api/roadmap-artifacts/{artifactId}} 仍返回完整
 * {@link RoadmapArtifactResponse}，其既有语义与全部写治理均未改变。</p>
 */
public record RoadmapArtifactSummaryResponse(
        String id,
        String workspaceId,
        ArtifactEvaluationMode evaluationMode,
        ArtifactStatus status,
        int submissionVersion,
        RoadmapNodeSnapshot roadmapNode,
        Integer rubricScore,
        String rubricFeedback,
        Boolean sensitiveScanPassed,
        Instant acceptedAt,
        Instant createdAt,
        List<ReviewSummary> reviewHistory
) {

    /** 只从已按 owner 过滤的服务读模型派生，绝不读取实体路径或私有内容。 */
    public static RoadmapArtifactSummaryResponse from(RoadmapArtifactResponse artifact) {
        return new RoadmapArtifactSummaryResponse(
                artifact.id(),
                artifact.workspaceId(),
                artifact.evaluationMode(),
                artifact.status(),
                artifact.submissionVersion(),
                new RoadmapNodeSnapshot(
                        artifact.roadmapNode().id(),
                        artifact.roadmapNode().moduleId(),
                        artifact.roadmapNode().stageId(),
                        artifact.roadmapNode().title(),
                        artifact.roadmapNode().moduleTitle(),
                        artifact.roadmapNode().stageTitle()),
                artifact.rubricScore(),
                artifact.rubricFeedback(),
                artifact.sensitiveScanPassed(),
                artifact.acceptedAt(),
                artifact.createdAt(),
                artifact.reviewHistory().stream().map(ReviewSummary::from).toList());
    }

    /** 所属节点快照：只保留可展示的标识与标题。 */
    public record RoadmapNodeSnapshot(
            String id,
            String moduleId,
            String stageId,
            String title,
            String moduleTitle,
            String stageTitle
    ) { }

    /** 评审事件的最小展示投影：状态流转、事件类型、分数与时间。 */
    public record ReviewSummary(
            ArtifactStatus toStatus,
            String eventType,
            Integer score,
            Instant createdAt
    ) {
        static ReviewSummary from(RoadmapArtifactResponse.ReviewEvent event) {
            return new ReviewSummary(
                    event.toStatus(), event.eventType(), event.score(), event.createdAt());
        }
    }
}
