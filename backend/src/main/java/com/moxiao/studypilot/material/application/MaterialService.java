package com.moxiao.studypilot.material.application;

import com.moxiao.studypilot.material.api.CreateMaterialRequest;
import com.moxiao.studypilot.material.api.UpdateMaterialProcessingRequest;
import com.moxiao.studypilot.material.infrastructure.MaterialEntity;
import com.moxiao.studypilot.material.infrastructure.MaterialJpaRepository;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class MaterialService {

    private final MaterialJpaRepository repository;

    public MaterialService(MaterialJpaRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public MaterialEntity create(String ownerId, CreateMaterialRequest request) {
        return repository.save(new MaterialEntity(
                UUID.randomUUID().toString(),
                ownerId,
                request.title().trim(),
                request.materialType(),
                request.category(),
                request.privacyLevel(),
                request.sourceUrl(),
                Instant.now()
        ));
    }

    @Transactional(readOnly = true)
    public List<MaterialEntity> list(String ownerId) {
        return repository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId);
    }

    @Transactional(readOnly = true)
    public MaterialEntity get(String ownerId, String materialId) {
        return repository.findByIdAndOwnerId(materialId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("学习资料不存在"));
    }

    @Transactional
    public MaterialEntity updateProcessing(
            String materialId,
            UpdateMaterialProcessingRequest request
    ) {
        MaterialEntity material = repository.findById(materialId)
                .orElseThrow(() -> new ResourceNotFoundException("学习资料不存在"));
        material.updateProcessingResult(
                request.status(),
                request.summary(),
                request.tags(),
                request.knowledgePoints(),
                request.contentReference(),
                request.failureReason(),
                Instant.now()
        );
        return material;
    }
}
