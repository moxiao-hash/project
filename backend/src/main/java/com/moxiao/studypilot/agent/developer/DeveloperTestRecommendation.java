package com.moxiao.studypilot.agent.developer;

import com.moxiao.studypilot.agent.runner.RunnerTemplateType;

import java.util.List;

public record DeveloperTestRecommendation(
        String workspaceId,
        List<RunnerTemplateType> templates,
        boolean requiresDependencyPreparation,
        List<String> reasons
) { }
