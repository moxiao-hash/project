package com.moxiao.studypilot.roadmap.application;

import com.moxiao.studypilot.roadmap.infrastructure.ArtifactSensitiveScanner;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapArtifactEntity;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Service
public class ArtifactReviewRubricEvaluator {

    private final ArtifactSensitiveScanner sensitiveScanner;

    public ArtifactReviewRubricEvaluator(ArtifactSensitiveScanner sensitiveScanner) {
        this.sensitiveScanner = sensitiveScanner;
    }

    public record EvaluationResult(
            int score,
            String feedback,
            boolean passed,
            boolean sensitiveScanPassed,
            String sensitiveFindings,
            String breakdownJson
    ) {
    }

    public EvaluationResult evaluate(RoadmapArtifactEntity artifact) {
        Path artifactPath = Path.of(artifact.getCanonicalPath());
        ArtifactSensitiveScanner.ScanResult scan = sensitiveScanner.scan(artifactPath);

        if (!scan.passed()) {
            return new EvaluationResult(
                    0,
                    "敏感文件扫描未通过，存在安全风险: " + scan.findings(),
                    false,
                    false,
                    scan.findings(),
                    "{\"security\":0,\"functional\":0,\"quality\":0}"
            );
        }

        String evidence = artifact.getTestEvidence() == null ? "" : artifact.getTestEvidence().toLowerCase();
        boolean hasPassEvidence = evidence.contains("pass") || evidence.contains("success")
                || evidence.contains("tests run") || evidence.contains("ok");

        int functionalScore = hasPassEvidence ? 40 : 20;
        int securityScore = 30; // Sensitive scan passed
        int qualityScore = artifact.getDescription() != null && artifact.getDescription().length() >= 10 ? 15 : 10;
        int totalScore = functionalScore + securityScore + qualityScore;

        boolean passed = totalScore >= 70;
        String feedback = passed
                ? "成果物验收通过（" + totalScore + "分），测试证据充分且无敏感信息泄露。"
                : "成果物未达标（" + totalScore + "分），请补充更完整的自动化测试验证证据后再提交。";

        String breakdownJson = String.format(
                "{\"functional\":%d,\"security\":%d,\"quality\":%d}",
                functionalScore, securityScore, qualityScore);

        return new EvaluationResult(
                totalScore,
                feedback,
                passed,
                true,
                null,
                breakdownJson
        );
    }
}
