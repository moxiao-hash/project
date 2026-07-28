package com.moxiao.studypilot.aicredential.infrastructure;

import com.moxiao.studypilot.aicredential.domain.AiProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AiCredentialJpaRepository extends JpaRepository<AiCredentialEntity, String> {
    Optional<AiCredentialEntity> findByOwnerIdAndProvider(String ownerId, AiProvider provider);
    void deleteByOwnerIdAndProvider(String ownerId, AiProvider provider);
}
