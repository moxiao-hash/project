package com.moxiao.studypilot.roadmap.application;

import com.moxiao.studypilot.agent.application.AgentGatewayService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/** Java-only gateway for the user-confirmed DeepSeek artifact review call. */
@Component
public class ArtifactReviewAiClient {
    private final AgentGatewayService gateway;
    private final ObjectMapper objectMapper;

    public ArtifactReviewAiClient(AgentGatewayService gateway, ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.objectMapper = objectMapper;
    }

    public ArtifactAiReviewResult evaluate(String ownerId, ArtifactAiReviewRequest request) {
        JsonNode body = gateway.post(
                "/internal/artifacts/evaluate",
                objectMapper.valueToTree(request),
                ownerId
        ).body();
        ArtifactAiReviewResult result = objectMapper.treeToValue(body, ArtifactAiReviewResult.class);
        int sum = result.functionalCorrectness() + result.requirementsCompleteness()
                + result.testQuality() + result.codeQuality();
        if (result.score() != sum || result.score() < 0 || result.score() > 100) {
            throw new IllegalStateException("AI 服务返回了无效的成果评审分数");
        }
        return result;
    }

    public record ArtifactAiReviewRequest(
            String artifactId,
            String description,
            RunnerEvidence runnerEvidence,
            List<ReviewFile> files
    ) { }

    public record RunnerEvidence(
            String executionId,
            String templateType,
            boolean success,
            String summary
    ) { }

    public record ReviewFile(String path, String sha256, String content) { }

    public record ArtifactAiReviewResult(
            int score,
            int functionalCorrectness,
            int requirementsCompleteness,
            int testQuality,
            int codeQuality,
            String feedback,
            List<String> strengths,
            List<String> issues,
            String modelName
    ) { }
}
