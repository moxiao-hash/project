package com.moxiao.studypilot.roadmap.application;

import com.moxiao.studypilot.agent.domain.ExecutionStatus;
import com.moxiao.studypilot.agent.runner.RunnerExecutionEntity;
import com.moxiao.studypilot.agent.runner.RunnerExecutionJpaRepository;
import com.moxiao.studypilot.agent.runner.RunnerTemplateType;
import com.moxiao.studypilot.shared.error.ConflictException;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

/** Validates that AI review is backed by a real, successful test execution. */
@Component
public class ArtifactRunnerEvidenceVerifier {
    private static final Set<RunnerTemplateType> TEST_TEMPLATES = Set.of(
            RunnerTemplateType.MAVEN_TEST,
            RunnerTemplateType.NPM_TEST,
            RunnerTemplateType.PYTEST
    );

    private final RunnerExecutionJpaRepository repository;

    public ArtifactRunnerEvidenceVerifier(RunnerExecutionJpaRepository repository) {
        this.repository = repository;
    }

    public VerifiedRunnerEvidence verify(
            String ownerId, String workspaceId, String runnerExecutionId,
            Instant artifactCreatedAt
    ) {
        RunnerExecutionEntity execution = repository
                .findByIdAndOwnerId(runnerExecutionId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Runner 测试记录不存在"));
        if (!execution.getWorkspaceId().equals(workspaceId)) {
            throw new ConflictException("Runner 测试与成果工作区不一致");
        }
        if (!TEST_TEMPLATES.contains(execution.getTemplateType())
                || execution.getStatus() != ExecutionStatus.SUCCEEDED
                || !Boolean.TRUE.equals(execution.getSuccess())
                || execution.getExecutedAt() == null) {
            throw new ConflictException("成果评审前必须通过真实 Runner 测试");
        }
        if (execution.getExecutedAt().isBefore(artifactCreatedAt)) {
            throw new ConflictException("Runner 测试早于本次成果提交，请重新运行测试");
        }
        String summary = execution.getStdoutSummary();
        if (summary == null || summary.isBlank()) {
            summary = "Runner 测试成功，退出码 " + execution.getExitCode();
        }
        return new VerifiedRunnerEvidence(
                execution.getId(), execution.getTemplateType().name(), summary,
                execution.getExecutedAt());
    }

    public record VerifiedRunnerEvidence(
            String executionId,
            String templateType,
            String summary,
            Instant executedAt
    ) { }
}
