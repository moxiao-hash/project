package com.moxiao.studypilot.material.infrastructure;

import com.moxiao.studypilot.material.domain.MaterialProcessingStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MaterialJpaRepository extends JpaRepository<MaterialEntity, String> {

    List<MaterialEntity> findAllByOwnerIdOrderByCreatedAtDesc(String ownerId);

    Optional<MaterialEntity> findByIdAndOwnerId(String id, String ownerId);

    long countByOwnerIdAndProcessingStatus(
            String ownerId,
            MaterialProcessingStatus processingStatus
    );
}
