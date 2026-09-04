package com.moxiao.studypilot.roadmap.application;

import com.moxiao.studypilot.roadmap.api.CreateProjectWorkspaceRequest;
import com.moxiao.studypilot.roadmap.api.CreateRoadmapArtifactRequest;
import com.moxiao.studypilot.roadmap.api.ProjectWorkspaceResponse;
import com.moxiao.studypilot.roadmap.api.RoadmapArtifactResponse;
import com.moxiao.studypilot.roadmap.domain.ArtifactEvaluationMode;
import com.moxiao.studypilot.roadmap.domain.ArtifactStatus;
import com.moxiao.studypilot.roadmap.infrastructure.ProjectWorkspaceEntity;
import com.moxiao.studypilot.roadmap.infrastructure.ProjectWorkspaceJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapArtifactEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapArtifactJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapArtifactReviewEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapArtifactReviewJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapModuleEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapModuleJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapStageEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapStageJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapEntity;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapNodeEntity;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapNodeJpaRepository;
import com.moxiao.studypilot.shared.error.ConflictException;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class RoadmapArtifactService {
    private static final String CURRENT = "CURRENT";

    private final ProjectWorkspaceJpaRepository workspaceRepository;
    private final RoadmapArtifactJpaRepository artifactRepository;
    private final RoadmapArtifactReviewJpaRepository reviewRepository;
    private final UserRoadmapJpaRepository enrollmentRepository;
    private final UserRoadmapNodeJpaRepository stateRepository;
    private final RoadmapNodeJpaRepository nodeRepository;
    private final RoadmapModuleJpaRepository moduleRepository;
    private final RoadmapStageJpaRepository stageRepository;
    private final ObjectMapper objectMapper;

    public RoadmapArtifactService(
            ProjectWorkspaceJpaRepository workspaceRepository,
            RoadmapArtifactJpaRepository artifactRepository,
            RoadmapArtifactReviewJpaRepository reviewRepository,
            UserRoadmapJpaRepository enrollmentRepository,
            UserRoadmapNodeJpaRepository stateRepository,
            RoadmapNodeJpaRepository nodeRepository,
            RoadmapModuleJpaRepository moduleRepository,
            RoadmapStageJpaRepository stageRepository,
            ObjectMapper objectMapper
    ) {
        this.workspaceRepository = workspaceRepository;
        this.artifactRepository = artifactRepository;
        this.reviewRepository = reviewRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.stateRepository = stateRepository;
        this.nodeRepository = nodeRepository;
        this.moduleRepository = moduleRepository;
        this.stageRepository = stageRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ProjectWorkspaceResponse createWorkspace(
            String ownerId,
            CreateProjectWorkspaceRequest request
    ) {
        Path canonicalRoot = canonicalWorkspaceRoot(request.rootPath());
        String rootPath = canonicalRoot.toString();
        ProjectWorkspaceEntity workspace = workspaceRepository
                .findByOwnerIdAndRootPath(ownerId, rootPath)
                .orElseGet(() -> workspaceRepository.save(new ProjectWorkspaceEntity(
                        UUID.randomUUID().toString(), ownerId, request.name().trim(),
                        rootPath, sha256(rootPath), Instant.now())));
        return ProjectWorkspaceResponse.from(workspace);
    }

    @Transactional(readOnly = true)
    public List<ProjectWorkspaceResponse> workspaces(String ownerId) {
        return workspaceRepository.findAllByOwnerIdOrderByCreatedAtAsc(ownerId)
                .stream().map(ProjectWorkspaceResponse::from).toList();
    }

    @Transactional
    public RoadmapArtifactResponse submit(String ownerId, CreateRoadmapArtifactRequest request) {
        RoadmapArtifactEntity existing = artifactRepository
                .findByOwnerIdAndIdempotencyKey(ownerId, request.idempotencyKey())
                .orElse(null);
        if (existing != null) {
            requireSameRequest(existing, request);
            return response(existing);
        }

        ProjectWorkspaceEntity workspace = workspaceRepository
                .findByIdAndOwnerId(request.workspaceId(), ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("工作区不存在"));
        Path canonicalEvidence = canonicalArtifactPath(
                Path.of(workspace.getRootPath()), request.relativePath());

        UserRoadmapEntity enrollment = enrollmentRepository
                .findByOwnerIdAndActiveSlotForUpdate(ownerId, CURRENT)
                .orElseThrow(() -> new ResourceNotFoundException("当前学习路线不存在"));
        UserRoadmapNodeEntity state = stateRepository
                .findByUserRoadmapIdAndNodeIdForUpdate(enrollment.getId(), request.roadmapNodeId())
                .orElseThrow(() -> new ResourceNotFoundException("当前路线节点不存在"));
        RoadmapNodeEntity node = nodeRepository
                .findByIdAndTemplateId(request.roadmapNodeId(), enrollment.getTemplateId())
                .orElseThrow(() -> new ResourceNotFoundException("路线节点不存在"));
        RoadmapModuleEntity module = moduleRepository
                .findByIdAndTemplateId(node.getModuleId(), enrollment.getTemplateId())
                .orElseThrow(() -> new ResourceNotFoundException("路线模块不存在"));
        RoadmapStageEntity stage = stageRepository
                .findByIdAndTemplateId(node.getStageId(), enrollment.getTemplateId())
                .orElseThrow(() -> new ResourceNotFoundException("路线阶段不存在"));

        if (state.getArtifactStatus() == ArtifactStatus.NOT_REQUIRED) {
            throw new ConflictException("该路线节点不要求提交实践成果");
        }

        Instant now = Instant.now();
        int submissionVersion = Math.toIntExact(
                artifactRepository.countByUserRoadmapNodeId(state.getId()) + 1);
        RoadmapArtifactEntity artifact = artifactRepository.save(new RoadmapArtifactEntity(
                UUID.randomUUID().toString(), ownerId, workspace.getId(), enrollment.getId(),
                state.getId(), node.getId(), module.getId(), stage.getId(), node.getTitle(),
                module.getTitle(), stage.getTitle(), normalizedRelativePath(request.relativePath()),
                canonicalEvidence.toString(), request.description().trim(),
                request.testEvidence().trim(), evaluationMode(node), submissionVersion,
                request.idempotencyKey(), now));
        reviewRepository.save(new RoadmapArtifactReviewEntity(
                UUID.randomUUID().toString(), artifact.getId(), ownerId,
                state.getArtifactStatus(), ArtifactStatus.SUBMITTED, "SUBMITTED",
                "用户提交实践成果，等待后续验收。", now));
        state.submitArtifact(now);
        return response(artifact);
    }

    @Transactional(readOnly = true)
    public RoadmapArtifactResponse artifact(String ownerId, String artifactId) {
        RoadmapArtifactEntity artifact = artifactRepository.findByIdAndOwnerId(artifactId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("实践成果不存在"));
        return response(artifact);
    }

    private RoadmapArtifactResponse response(RoadmapArtifactEntity artifact) {
        return RoadmapArtifactResponse.from(
                artifact,
                reviewRepository.findAllByArtifactIdOrderByCreatedAtAsc(artifact.getId()));
    }

    private Path canonicalWorkspaceRoot(String rawPath) {
        Path requested = Path.of(rawPath.trim());
        if (!requested.isAbsolute()) {
            throw new IllegalArgumentException("工作区必须使用绝对路径");
        }
        if (Files.isSymbolicLink(requested)) {
            throw new IllegalArgumentException("工作区根目录不能是符号链接");
        }
        try {
            Path canonical = requested.toRealPath();
            if (!Files.isDirectory(canonical)) {
                throw new IllegalArgumentException("工作区路径必须是已存在的目录");
            }
            return canonical;
        } catch (IOException exception) {
            throw new IllegalArgumentException("工作区路径不存在或不可访问", exception);
        }
    }

    private Path canonicalArtifactPath(Path root, String rawRelativePath) {
        Path relative = Path.of(rawRelativePath.trim());
        if (relative.isAbsolute() || relative.getNameCount() == 0) {
            throw new IllegalArgumentException("成果路径必须是工作区内的相对路径");
        }
        for (Path part : relative) {
            if ("..".equals(part.toString())) {
                throw new IllegalArgumentException("成果路径不能包含目录穿越");
            }
        }
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("成果路径超出工作区范围");
        }
        rejectSymbolicLinkComponents(root, relative);
        try {
            Path canonical = candidate.toRealPath();
            if (!canonical.startsWith(root)) {
                throw new IllegalArgumentException("成果路径超出工作区范围");
            }
            return canonical;
        } catch (IOException exception) {
            throw new IllegalArgumentException("成果路径不存在或不可访问", exception);
        }
    }

    private void rejectSymbolicLinkComponents(Path root, Path relative) {
        Path cursor = root;
        for (Path part : relative) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) {
                throw new IllegalArgumentException("成果路径不能经过符号链接");
            }
        }
    }

    private String normalizedRelativePath(String rawRelativePath) {
        return Path.of(rawRelativePath.trim()).normalize().toString();
    }

    private ArtifactEvaluationMode evaluationMode(RoadmapNodeEntity node) {
        try {
            JsonNode value = objectMapper.readTree(node.getArtifactRequirementJson())
                    .get("evaluationMode");
            if (value == null || !value.isString()) {
                throw new IllegalStateException("路线节点实践要求配置无效: " + node.getId());
            }
            return ArtifactEvaluationMode.valueOf(value.asText());
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalStateException) {
                throw exception;
            }
            throw new IllegalStateException("路线节点实践要求配置无效: " + node.getId(), exception);
        }
    }

    private void requireSameRequest(
            RoadmapArtifactEntity existing,
            CreateRoadmapArtifactRequest request
    ) {
        if (!existing.getWorkspaceId().equals(request.workspaceId())
                || !existing.getRoadmapNodeId().equals(request.roadmapNodeId())
                || !existing.getRelativePath().equals(normalizedRelativePath(request.relativePath()))
                || !existing.getDescription().equals(request.description().trim())
                || !existing.getTestEvidence().equals(request.testEvidence().trim())) {
            throw new ConflictException("成果幂等键已用于其他提交内容");
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
    }
}
