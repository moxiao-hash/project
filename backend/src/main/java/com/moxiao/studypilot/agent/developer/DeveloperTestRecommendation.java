package com.moxiao.studypilot.agent.developer;

import com.moxiao.studypilot.agent.runner.RunnerTemplateType;

import java.util.List;

/**
 * 测试推荐结果。
 *
 * <p>{@code executions} 为每个推荐模板附上**已验证的工作区相对工作目录**，Runner 据此在
 * 对应子项目里执行，而不是永远在工作区根目录。{@code templates} 保留为等价的模板清单，
 * 兼容既有调用方。</p>
 */
public record DeveloperTestRecommendation(
        String workspaceId,
        List<RunnerTemplateType> templates,
        List<RecommendedExecution> executions,
        boolean requiresDependencyPreparation,
        List<String> reasons
) {

    /** 一条推荐执行：模板 + 规范化的相对工作目录（{@code "."} 表示工作区根目录）。 */
    public record RecommendedExecution(
            RunnerTemplateType templateType,
            String workingDirectory
    ) { }
}
