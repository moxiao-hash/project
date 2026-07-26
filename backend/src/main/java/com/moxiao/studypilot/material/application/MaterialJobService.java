package com.moxiao.studypilot.material.application;

import com.moxiao.studypilot.material.api.MaterialJobResponse;
import com.moxiao.studypilot.material.api.CompleteMaterialJobRequest;
import com.moxiao.studypilot.material.infrastructure.MaterialChunkEntity;
import com.moxiao.studypilot.material.infrastructure.MaterialChunkJpaRepository;
import com.moxiao.studypilot.material.infrastructure.MaterialEntity;
import com.moxiao.studypilot.material.infrastructure.MaterialJpaRepository;
import com.moxiao.studypilot.material.infrastructure.MaterialProcessingJobEntity;
import com.moxiao.studypilot.material.infrastructure.MaterialProcessingJobJpaRepository;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class MaterialJobService {

    private final MaterialProcessingJobJpaRepository jobRepository;
    private final MaterialJpaRepository materialRepository;
    private final MaterialChunkJpaRepository chunkRepository;

    public MaterialJobService(
            MaterialProcessingJobJpaRepository jobRepository,
            MaterialJpaRepository materialRepository,
            MaterialChunkJpaRepository chunkRepository
    ) {
        this.jobRepository = jobRepository;
        this.materialRepository = materialRepository;
        this.chunkRepository = chunkRepository;
    }

    @Transactional
    public MaterialJobResponse claim(String workerId, int leaseSeconds) {
        Instant now = Instant.now();
        MaterialProcessingJobEntity job = jobRepository
                .findClaimable(now, PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("暂无待处理资料"));
        job.claim(workerId, now.plusSeconds(leaseSeconds), now);
        MaterialEntity material = material(job.getMaterialId());
        material.markProcessing(now);
        return MaterialJobResponse.from(job, material);
    }

    @Transactional
    public MaterialJobResponse heartbeat(
            String jobId,
            String workerId,
            int leaseSeconds
    ) {
        Instant now = Instant.now();
        MaterialProcessingJobEntity job = job(jobId);
        job.heartbeat(workerId, now.plusSeconds(leaseSeconds), now);
        return MaterialJobResponse.from(job, material(job.getMaterialId()));
    }

    @Transactional
    public MaterialJobResponse fail(String jobId, String workerId, String error) {
        Instant now = Instant.now();
        MaterialProcessingJobEntity job = job(jobId);
        job.fail(workerId, error, now);
        MaterialEntity material = material(job.getMaterialId());
        if (job.getAttemptCount() >= 3) {
            material.markFailed(error, now);
        }
        return MaterialJobResponse.from(job, material);
    }

    @Transactional
    public MaterialJobResponse complete(
            String jobId,
            CompleteMaterialJobRequest request
    ) {
        Instant now = Instant.now();
        MaterialProcessingJobEntity job = job(jobId);
        job.complete(request.workerId(), now);
        MaterialEntity material = material(job.getMaterialId());

        chunkRepository.deleteAllByMaterialId(material.getId());
        chunkRepository.saveAll(request.chunks().stream()
                .map(chunk -> new MaterialChunkEntity(
                        java.util.UUID.randomUUID().toString(),
                        material.getId(),
                        chunk.position(),
                        chunk.text(),
                        chunk.locator()
                ))
                .toList());
        material.completeProcessing(
                request.summary(),
                request.tags(),
                request.knowledgePoints(),
                request.warnings(),
                request.contentReference(),
                now
        );
        return MaterialJobResponse.from(job, material);
    }

    private MaterialProcessingJobEntity job(String jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("资料处理任务不存在"));
    }

    private MaterialEntity material(String materialId) {
        return materialRepository.findById(materialId)
                .orElseThrow(() -> new ResourceNotFoundException("学习资料不存在"));
    }
}
