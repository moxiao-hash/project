package com.moxiao.studypilot.roadmap.application;

import com.moxiao.studypilot.roadmap.api.RoadmapEnrollmentResponse;
import com.moxiao.studypilot.roadmap.domain.AvailabilityStatus;
import com.moxiao.studypilot.roadmap.domain.RoadmapPublicationStatus;
import com.moxiao.studypilot.roadmap.domain.UserRoadmapStatus;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodePrerequisiteJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapTemplateEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapTemplateJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapEntity;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapNodeEntity;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapNodeJpaRepository;
import com.moxiao.studypilot.shared.error.ConflictException;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class RoadmapEnrollmentService {

    private static final String CURRENT = "CURRENT";

    private final RoadmapTemplateJpaRepository templateRepository;
    private final UserRoadmapJpaRepository userRoadmapRepository;
    private final RoadmapNodeJpaRepository nodeRepository;
    private final RoadmapNodePrerequisiteJpaRepository prerequisiteRepository;
    private final UserRoadmapNodeJpaRepository userNodeRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final Runnable beforeInsert;

    @Autowired
    public RoadmapEnrollmentService(
            RoadmapTemplateJpaRepository templateRepository,
            UserRoadmapJpaRepository userRoadmapRepository,
            RoadmapNodeJpaRepository nodeRepository,
            RoadmapNodePrerequisiteJpaRepository prerequisiteRepository,
            UserRoadmapNodeJpaRepository userNodeRepository,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this(templateRepository, userRoadmapRepository, nodeRepository, prerequisiteRepository,
                userNodeRepository, objectMapper, transactionManager, () -> { });
    }

    RoadmapEnrollmentService(
            RoadmapTemplateJpaRepository templateRepository,
            UserRoadmapJpaRepository userRoadmapRepository,
            RoadmapNodeJpaRepository nodeRepository,
            RoadmapNodePrerequisiteJpaRepository prerequisiteRepository,
            UserRoadmapNodeJpaRepository userNodeRepository,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            Runnable beforeInsert
    ) {
        this.templateRepository = templateRepository;
        this.userRoadmapRepository = userRoadmapRepository;
        this.nodeRepository = nodeRepository;
        this.prerequisiteRepository = prerequisiteRepository;
        this.userNodeRepository = userNodeRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.beforeInsert = beforeInsert;
    }

    public RoadmapEnrollmentResponse enroll(String ownerId, String roadmapCode, int templateVersion) {
        try {
            return transactionTemplate.execute(status -> enrollInTransaction(ownerId, roadmapCode, templateVersion));
        } catch (ConcurrentEnrollmentInsertException collision) {
            return recoverConcurrentEnrollment(ownerId, roadmapCode, templateVersion, collision);
        }
    }

    private RoadmapEnrollmentResponse enrollInTransaction(
            String ownerId,
            String roadmapCode,
            int templateVersion
    ) {
        RoadmapTemplateEntity template = publishedTemplate(roadmapCode, templateVersion);
        var sameTemplate = userRoadmapRepository.findByOwnerIdAndTemplateId(ownerId, template.getId());
        if (sameTemplate.isPresent() && sameTemplate.get().getStatus() == UserRoadmapStatus.ACTIVE) {
            return RoadmapEnrollmentResponse.from(sameTemplate.get(), template);
        }
        if (userRoadmapRepository.findByOwnerIdAndActiveSlot(ownerId, CURRENT).isPresent()
                || sameTemplate.isPresent()) {
            throw new ConflictException("已有生效中的学习路线");
        }

        beforeInsert.run();
        Instant now = Instant.now();
        UserRoadmapEntity enrollment = new UserRoadmapEntity(
                UUID.randomUUID().toString(), ownerId, template.getId(), now);
        try {
            userRoadmapRepository.saveAndFlush(enrollment);
        } catch (DataIntegrityViolationException exception) {
            throw new ConcurrentEnrollmentInsertException(exception);
        }

        List<RoadmapNodeEntity> nodes = nodeRepository
                .findAllByTemplateIdOrderByStageIdAscNodeOrderAsc(template.getId());
        Set<String> nodesWithPrerequisites = prerequisiteRepository.findAllByTemplateId(template.getId())
                .stream()
                .map(edge -> edge.getNodeId())
                .collect(Collectors.toSet());
        List<UserRoadmapNodeEntity> states = nodes.stream()
                .map(node -> new UserRoadmapNodeEntity(
                        stableNodeStateId(enrollment.getId(), node.getId()),
                        enrollment.getId(),
                        node.getId(),
                        ownerId,
                        template.getId(),
                        nodesWithPrerequisites.contains(node.getId())
                                ? AvailabilityStatus.LOCKED : AvailabilityStatus.AVAILABLE,
                        artifactRequired(node),
                        now
                ))
                .toList();
        userNodeRepository.saveAll(states);
        userNodeRepository.flush();
        return RoadmapEnrollmentResponse.from(enrollment, template);
    }

    private RoadmapEnrollmentResponse recoverConcurrentEnrollment(
            String ownerId,
            String roadmapCode,
            int templateVersion,
            ConcurrentEnrollmentInsertException collision
    ) {
        RoadmapEnrollmentResponse recovered = transactionTemplate.execute(status -> {
            RoadmapTemplateEntity template = publishedTemplate(roadmapCode, templateVersion);
            return userRoadmapRepository.findByOwnerIdAndTemplateId(ownerId, template.getId())
                    .filter(enrollment -> enrollment.getStatus() == UserRoadmapStatus.ACTIVE)
                    .map(enrollment -> RoadmapEnrollmentResponse.from(enrollment, template))
                    .orElse(null);
        });
        if (recovered != null) {
            return recovered;
        }

        Boolean hasOtherActive = transactionTemplate.execute(status ->
                userRoadmapRepository.findByOwnerIdAndActiveSlot(ownerId, CURRENT).isPresent());
        if (Boolean.TRUE.equals(hasOtherActive)) {
            throw new ConflictException("已有生效中的学习路线");
        }
        throw collision.integrityViolation();
    }

    private RoadmapTemplateEntity publishedTemplate(String roadmapCode, int templateVersion) {
        return templateRepository.findByRoadmapCodeAndTemplateVersion(roadmapCode, templateVersion)
                .filter(template -> template.getPublicationStatus() == RoadmapPublicationStatus.PUBLISHED)
                .orElseThrow(() -> new ResourceNotFoundException("路线版本不存在"));
    }

    private boolean artifactRequired(RoadmapNodeEntity node) {
        try {
            JsonNode required = objectMapper.readTree(node.getArtifactRequirementJson()).get("required");
            if (required == null || !required.isBoolean()) {
                throw new IllegalStateException("路线节点产物要求配置无效: " + node.getId());
            }
            return required.asBoolean();
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalStateException) {
                throw exception;
            }
            throw new IllegalStateException("路线节点产物要求配置无效: " + node.getId(), exception);
        }
    }

    private static String stableNodeStateId(String enrollmentId, String nodeId) {
        return UUID.nameUUIDFromBytes((enrollmentId + ":" + nodeId).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private static final class ConcurrentEnrollmentInsertException extends RuntimeException {
        private final DataIntegrityViolationException integrityViolation;

        private ConcurrentEnrollmentInsertException(DataIntegrityViolationException integrityViolation) {
            super(integrityViolation);
            this.integrityViolation = integrityViolation;
        }

        private DataIntegrityViolationException integrityViolation() {
            return integrityViolation;
        }
    }
}
