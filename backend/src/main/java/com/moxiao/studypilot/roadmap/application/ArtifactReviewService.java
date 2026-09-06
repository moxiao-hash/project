package com.moxiao.studypilot.roadmap.application;

import com.moxiao.studypilot.agent.infrastructure.AuditLogEntity;
import com.moxiao.studypilot.agent.infrastructure.AuditLogJpaRepository;
import com.moxiao.studypilot.notification.api.CreateNotificationRequest;
import com.moxiao.studypilot.notification.application.NotificationService;
import com.moxiao.studypilot.notification.domain.NotificationType;
import com.moxiao.studypilot.roadmap.api.ArtifactReviewPreviewResponse;
import com.moxiao.studypilot.roadmap.api.CreateArtifactReviewPreviewRequest;
import com.moxiao.studypilot.roadmap.api.RoadmapArtifactResponse;
import com.moxiao.studypilot.roadmap.domain.ArtifactReviewPreviewStatus;
import com.moxiao.studypilot.roadmap.domain.ArtifactStatus;
import com.moxiao.studypilot.roadmap.infrastructure.ArtifactReviewFileCollector;
import com.moxiao.studypilot.roadmap.infrastructure.ArtifactReviewFileCollector.CollectionResult;
import com.moxiao.studypilot.roadmap.infrastructure.ArtifactReviewFileCollector.FileSnapshot;
import com.moxiao.studypilot.roadmap.infrastructure.ArtifactReviewPreviewEntity;
import com.moxiao.studypilot.roadmap.infrastructure.ArtifactReviewPreviewJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapArtifactEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapArtifactJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapArtifactReviewEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapArtifactReviewJpaRepository;
import com.moxiao.studypilot.shared.error.ConflictException;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Coordinates preview, explicit confirmation and durable AI rubric recording. */
@Service
public class ArtifactReviewService {
    private static final Duration PREVIEW_LIFETIME = Duration.ofMinutes(10);

    private final RoadmapArtifactJpaRepository artifactRepository;
    private final RoadmapArtifactReviewJpaRepository reviewRepository;
    private final ArtifactReviewPreviewJpaRepository previewRepository;
    private final ArtifactRunnerEvidenceVerifier evidenceVerifier;
    private final ArtifactReviewFileCollector fileCollector;
    private final ArtifactReviewAiClient aiClient;
    private final AuditLogJpaRepository auditLogRepository;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public ArtifactReviewService(
            RoadmapArtifactJpaRepository artifactRepository,
            RoadmapArtifactReviewJpaRepository reviewRepository,
            ArtifactReviewPreviewJpaRepository previewRepository,
            ArtifactRunnerEvidenceVerifier evidenceVerifier,
            ArtifactReviewFileCollector fileCollector,
            ArtifactReviewAiClient aiClient,
            AuditLogJpaRepository auditLogRepository,
            NotificationService notificationService,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.artifactRepository = artifactRepository;
        this.reviewRepository = reviewRepository;
        this.previewRepository = previewRepository;
        this.evidenceVerifier = evidenceVerifier;
        this.fileCollector = fileCollector;
        this.aiClient = aiClient;
        this.auditLogRepository = auditLogRepository;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public ArtifactReviewPreviewResponse createPreview(
            String ownerId,
            String artifactId,
            CreateArtifactReviewPreviewRequest request
    ) {
        ArtifactReviewPreviewEntity entity = transactions.execute(status -> {
            RoadmapArtifactEntity artifact = requireArtifact(ownerId, artifactId);
            ArtifactReviewPreviewEntity existing = previewRepository
                    .findByOwnerIdAndIdempotencyKey(ownerId, request.idempotencyKey())
                    .orElse(null);
            if (existing != null) {
                if (!existing.getArtifactId().equals(artifactId)
                        || !existing.getRunnerExecutionId().equals(request.runnerExecutionId())) {
                    throw new ConflictException("评审预览幂等键已用于其他请求");
                }
                return existing;
            }
            if (artifact.getStatus() != ArtifactStatus.SUBMITTED) {
                throw new ConflictException("只有等待验收的成果可以创建 AI 评审预览");
            }
            ArtifactRunnerEvidenceVerifier.VerifiedRunnerEvidence evidence =
                    evidenceVerifier.verify(ownerId, artifact.getWorkspaceId(),
                            request.runnerExecutionId(), artifact.getCreatedAt());
            CollectionResult snapshot = fileCollector.collect(Path.of(artifact.getCanonicalPath()));
            if (!snapshot.passed()) {
                throw new ConflictException("成果源码未通过敏感扫描: " + snapshot.findings());
            }
            String manifestJson = objectMapper.writeValueAsString(snapshot.files());
            String excludedJson = objectMapper.writeValueAsString(snapshot.excludedPaths());
            Instant now = Instant.now();
            ArtifactReviewPreviewEntity preview = previewRepository.save(new ArtifactReviewPreviewEntity(
                    UUID.randomUUID().toString(), artifactId, ownerId,
                    evidence.executionId(), evidence.templateType(), safeSummary(evidence.summary()),
                    manifestJson, excludedJson, sha256(manifestJson), request.idempotencyKey(),
                    now.plus(PREVIEW_LIFETIME), now));
            audit(ownerId, "ARTIFACT_REVIEW_PREVIEW_CREATED", preview,
                    "已生成待确认源码清单，文件数 " + snapshot.files().size());
            return preview;
        });
        if (entity == null) {
            throw new IllegalStateException("评审预览创建事务未返回结果");
        }
        return response(entity);
    }

    public RoadmapArtifactResponse confirm(
            String ownerId, String artifactId, String previewId
    ) {
        Confirmation confirmation = transactions.execute(
                status -> claim(ownerId, artifactId, previewId));
        if (confirmation == null) {
            throw new IllegalStateException("评审确认事务未返回结果");
        }
        if (!confirmation.execute()) {
            return artifactResponse(confirmation.artifact());
        }

        try {
            ArtifactReviewAiClient.ArtifactAiReviewResult result = aiClient.evaluate(
                    ownerId,
                    buildAiRequest(confirmation.artifact(), confirmation.preview())
            );
            RoadmapArtifactEntity completed = transactions.execute(status ->
                    finish(ownerId, artifactId, previewId, result));
            if (completed == null) {
                throw new IllegalStateException("AI 评审完成事务未返回结果");
            }
            return artifactResponse(completed);
        } catch (RuntimeException exception) {
            transactions.executeWithoutResult(status -> fail(ownerId, artifactId, previewId));
            throw exception;
        }
    }

    private Confirmation claim(String ownerId, String artifactId, String previewId) {
        ArtifactReviewPreviewEntity preview = requirePreview(ownerId, artifactId, previewId);
        RoadmapArtifactEntity artifact = requireArtifact(ownerId, artifactId);
        if (preview.getStatus() == ArtifactReviewPreviewStatus.COMPLETED) {
            return new Confirmation(preview, artifact, false);
        }
        if (preview.getStatus() != ArtifactReviewPreviewStatus.WAITING_CONFIRMATION) {
            throw new ConflictException("当前评审预览不能确认");
        }
        if (preview.getExpiresAt().isBefore(Instant.now())) {
            throw new ConflictException("评审预览已过期，请重新生成文件清单");
        }
        CollectionResult current = fileCollector.collect(Path.of(artifact.getCanonicalPath()));
        String currentManifest = objectMapper.writeValueAsString(current.files());
        if (!current.passed() || !sha256(currentManifest).equals(preview.getSnapshotDigest())) {
            throw new ConflictException("成果文件在确认前已变化，请重新生成评审预览");
        }
        preview.reviewing(Instant.now());
        previewRepository.save(preview);
        audit(ownerId, "ARTIFACT_REVIEW_CONFIRMED", preview,
                "用户已确认发送清单内源码进行固定 Rubric 评审");
        return new Confirmation(preview, artifact, true);
    }

    private RoadmapArtifactEntity finish(
            String ownerId, String artifactId, String previewId,
            ArtifactReviewAiClient.ArtifactAiReviewResult result
    ) {
        ArtifactReviewPreviewEntity preview = requirePreview(ownerId, artifactId, previewId);
        RoadmapArtifactEntity artifact = artifactRepository.findOwnedForUpdate(artifactId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("实践成果不存在"));
        if (preview.getStatus() == ArtifactReviewPreviewStatus.COMPLETED) {
            return artifact;
        }
        if (preview.getStatus() != ArtifactReviewPreviewStatus.REVIEWING) {
            throw new ConflictException("评审预览状态已变化");
        }
        String breakdown = objectMapper.writeValueAsString(new RubricBreakdown(
                result.functionalCorrectness(), result.requirementsCompleteness(),
                result.testQuality(), result.codeQuality(), result.strengths(), result.issues(),
                result.modelName(), preview.getRunnerExecutionId()));
        Instant now = Instant.now();
        artifact.recordReview(result.score(), result.feedback(), true, null, now);
        reviewRepository.save(new RoadmapArtifactReviewEntity(
                UUID.randomUUID().toString(), artifactId, ownerId,
                artifact.getStatus(), artifact.getStatus(),
                result.score() >= 70 ? "AI_RUBRIC_PASSED" : "AI_RUBRIC_REJECTED",
                "DeepSeek 固定 Rubric 评审完成，模型 " + result.modelName(),
                result.score(), breakdown, now));
        preview.complete(result.modelName(), now);
        previewRepository.save(preview);
        audit(ownerId, "ARTIFACT_REVIEW_SUCCEEDED", preview,
                "AI Rubric 评审完成，得分 " + result.score());
        notificationService.create(new CreateNotificationRequest(
                ownerId,
                NotificationType.AGENT_ACTION_COMPLETED,
                "成果 AI 评审已完成",
                "固定 Rubric 得分 " + result.score() + "，请查看反馈并决定是否最终验收。"));
        return artifactRepository.save(artifact);
    }

    private void fail(String ownerId, String artifactId, String previewId) {
        ArtifactReviewPreviewEntity preview = requirePreview(ownerId, artifactId, previewId);
        if (preview.getStatus() == ArtifactReviewPreviewStatus.REVIEWING) {
            preview.fail("AI 评审失败，可创建新的评审预览后重试", Instant.now());
            previewRepository.save(preview);
            audit(ownerId, "ARTIFACT_REVIEW_FAILED", preview,
                    "AI 评审失败，源码清单需要重新确认后才能重试");
            notificationService.create(new CreateNotificationRequest(
                    ownerId,
                    NotificationType.AGENT_FAILED,
                    "成果 AI 评审失败",
                    "本次评审未产生有效分数，请重新生成预览后再试。"));
        }
    }

    private ArtifactReviewAiClient.ArtifactAiReviewRequest buildAiRequest(
            RoadmapArtifactEntity artifact,
            ArtifactReviewPreviewEntity preview
    ) {
        List<FileSnapshot> manifest = Arrays.asList(objectMapper.readValue(
                preview.getManifestJson(), FileSnapshot[].class));
        Path submittedPath = Path.of(artifact.getCanonicalPath());
        Path root = Files.isDirectory(submittedPath) ? submittedPath : submittedPath.getParent();
        List<ArtifactReviewAiClient.ReviewFile> files = manifest.stream().map(item -> {
            Path file = root.resolve(item.relativePath()).normalize();
            if (!file.startsWith(root)) {
                throw new ConflictException("评审文件路径无效");
            }
            try {
                return new ArtifactReviewAiClient.ReviewFile(
                        item.relativePath(), item.sha256(), Files.readString(file));
            } catch (IOException exception) {
                throw new ConflictException("确认后无法读取评审文件");
            }
        }).toList();
        return new ArtifactReviewAiClient.ArtifactAiReviewRequest(
                artifact.getId(), artifact.getDescription(),
                new ArtifactReviewAiClient.RunnerEvidence(
                        preview.getRunnerExecutionId(), preview.getRunnerTemplateType(), true,
                        preview.getRunnerSummary()),
                files);
    }

    private RoadmapArtifactEntity requireArtifact(String ownerId, String artifactId) {
        return artifactRepository.findByIdAndOwnerId(artifactId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("实践成果不存在"));
    }

    private ArtifactReviewPreviewEntity requirePreview(
            String ownerId, String artifactId, String previewId
    ) {
        return previewRepository.findOwnedForUpdate(previewId, artifactId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("评审预览不存在"));
    }

    private RoadmapArtifactResponse artifactResponse(RoadmapArtifactEntity artifact) {
        return RoadmapArtifactResponse.from(artifact,
                reviewRepository.findAllByArtifactIdOrderByCreatedAtAsc(artifact.getId()));
    }

    private ArtifactReviewPreviewResponse response(ArtifactReviewPreviewEntity preview) {
        List<FileSnapshot> files = Arrays.asList(objectMapper.readValue(
                preview.getManifestJson(), FileSnapshot[].class));
        List<String> excluded = Arrays.asList(objectMapper.readValue(
                preview.getExcludedPathsJson(), String[].class));
        long total = files.stream().mapToLong(FileSnapshot::size).sum();
        return new ArtifactReviewPreviewResponse(
                preview.getId(), preview.getArtifactId(), preview.getRunnerExecutionId(),
                preview.getRunnerTemplateType(), preview.getStatus(), files, excluded, total,
                preview.getExpiresAt(), preview.getModelName(), preview.getErrorMessage());
    }

    private String safeSummary(String value) {
        if (value == null) return "";
        return value.length() <= 4000 ? value : value.substring(0, 4000);
    }

    private void audit(
            String ownerId,
            String action,
            ArtifactReviewPreviewEntity preview,
            String details
    ) {
        auditLogRepository.save(new AuditLogEntity(
                ownerId, action, "ARTIFACT_REVIEW", preview.getId(), details, Instant.now()));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
    }

    private record Confirmation(
            ArtifactReviewPreviewEntity preview,
            RoadmapArtifactEntity artifact,
            boolean execute
    ) { }

    private record RubricBreakdown(
            int functionalCorrectness,
            int requirementsCompleteness,
            int testQuality,
            int codeQuality,
            List<String> strengths,
            List<String> issues,
            String modelName,
            String runnerExecutionId
    ) { }
}
