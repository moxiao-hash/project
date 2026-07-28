package com.moxiao.studypilot.aicredential.application;

import com.moxiao.studypilot.agent.infrastructure.AuditLogEntity;
import com.moxiao.studypilot.agent.infrastructure.AuditLogJpaRepository;
import com.moxiao.studypilot.aicredential.api.AiProviderStatusResponse;
import com.moxiao.studypilot.aicredential.api.AiSettingsResponse;
import com.moxiao.studypilot.aicredential.domain.AiProvider;
import com.moxiao.studypilot.aicredential.infrastructure.AiCredentialEntity;
import com.moxiao.studypilot.aicredential.infrastructure.AiCredentialJpaRepository;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class AiCredentialService {

    private final AiCredentialJpaRepository repository;
    private final AuditLogJpaRepository auditRepository;
    private final AiCredentialCipher cipher;
    private final DefaultCredentialStatusClient defaultStatusClient;
    private final String modelName;

    public AiCredentialService(
            AiCredentialJpaRepository repository,
            AuditLogJpaRepository auditRepository,
            AiCredentialCipher cipher,
            DefaultCredentialStatusClient defaultStatusClient,
            @Value("${MODEL_NAME:deepseek-v4-pro}") String modelName
    ) {
        this.repository = repository;
        this.auditRepository = auditRepository;
        this.cipher = cipher;
        this.defaultStatusClient = defaultStatusClient;
        this.modelName = modelName;
    }

    @Transactional(readOnly = true)
    public AiSettingsResponse settings(String ownerId) {
        DefaultCredentialStatusClient.DefaultStatuses defaults =
                defaultStatusClient.fetch();
        AiProviderStatusResponse deepseek =
                status(ownerId, AiProvider.DEEPSEEK, defaults.deepseek(), defaults.available());
        AiProviderStatusResponse tavily =
                status(ownerId, AiProvider.TAVILY, defaults.tavily(), defaults.available());
        return new AiSettingsResponse(
                "deepseek",
                modelName,
                deepseek,
                tavily,
                deepseek.configured(),
                deepseek.maskedSuffix(),
                tavily.configured(),
                tavily.maskedSuffix(),
                defaults.available() ? null : "AI 服务暂时不可用，无法确认服务器默认配置"
        );
    }

    @Transactional
    public AiSettingsResponse save(String ownerId, AiProvider provider, String rawApiKey) {
        String apiKey = rawApiKey == null ? "" : rawApiKey.trim();
        if (apiKey.length() < 8 || apiKey.length() > 512) {
            throw new IllegalArgumentException("API Key 长度必须在 8 到 512 个字符之间");
        }
        AiCredentialCipher.EncryptedValue encrypted =
                cipher.encrypt(ownerId, provider, apiKey);
        Instant now = Instant.now();
        repository.findByOwnerIdAndProvider(ownerId, provider)
                .ifPresentOrElse(
                        entity -> entity.replace(encrypted.ciphertext(), encrypted.iv(), now),
                        () -> repository.save(new AiCredentialEntity(
                                UUID.randomUUID().toString(),
                                ownerId,
                                provider,
                                encrypted.ciphertext(),
                                encrypted.iv(),
                                now
                        ))
                );
        audit(ownerId, "AI_CREDENTIAL_UPDATED", provider);
        return settings(ownerId);
    }

    @Transactional
    public AiSettingsResponse delete(String ownerId, AiProvider provider) {
        repository.deleteByOwnerIdAndProvider(ownerId, provider);
        audit(ownerId, "AI_CREDENTIAL_DELETED", provider);
        return settings(ownerId);
    }

    @Transactional(readOnly = true)
    public String resolveUserKey(String ownerId, AiProvider provider) {
        AiCredentialEntity entity = repository.findByOwnerIdAndProvider(ownerId, provider)
                .orElseThrow(() -> new ResourceNotFoundException("用户未配置该 AI 服务凭据"));
        return cipher.decrypt(
                ownerId,
                provider,
                entity.getCiphertext(),
                entity.getIv()
        );
    }

    private AiProviderStatusResponse status(
            String ownerId,
            AiProvider provider,
            DefaultCredentialStatusClient.SafeStatus fallback,
            boolean defaultsAvailable
    ) {
        return repository.findByOwnerIdAndProvider(ownerId, provider)
                .map(entity -> {
                    String value = cipher.decrypt(
                            ownerId,
                            provider,
                            entity.getCiphertext(),
                            entity.getIv()
                    );
                    return new AiProviderStatusResponse(true, "USER", suffix(value), true);
                })
                .orElseGet(() -> {
                    return new AiProviderStatusResponse(
                            fallback.configured(),
                            fallback.configured() ? "SERVER_DEFAULT" : "NONE",
                            fallback.maskedSuffix(),
                            defaultsAvailable && fallback.configured()
                    );
                });
    }

    private String suffix(String value) {
        int start = Math.max(0, value.length() - 4);
        return value.substring(start);
    }

    private void audit(String ownerId, String action, AiProvider provider) {
        auditRepository.save(new AuditLogEntity(
                ownerId,
                action,
                "AI_CREDENTIAL",
                provider.name(),
                "服务商 " + provider.name(),
                Instant.now()
        ));
    }
}
