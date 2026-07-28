package com.moxiao.studypilot.aicredential.application;

import com.moxiao.studypilot.agent.infrastructure.AuditLogJpaRepository;
import com.moxiao.studypilot.aicredential.domain.AiProvider;
import com.moxiao.studypilot.aicredential.infrastructure.AiCredentialJpaRepository;
import com.moxiao.studypilot.shared.error.ConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiCredentialConcurrencyTest {

    @Test
    void concurrentFirstInsertReturnsStableConflictInsteadOfServerError() {
        AiCredentialJpaRepository repository = mock(AiCredentialJpaRepository.class);
        when(repository.findByOwnerIdAndProvider("owner", AiProvider.DEEPSEEK))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate ciphertext row"));
        AiCredentialService service = new AiCredentialService(
                repository,
                mock(AuditLogJpaRepository.class),
                new AiCredentialCipher(Base64.getEncoder().encodeToString(new byte[32])),
                mock(DefaultCredentialStatusClient.class)
        );

        ConflictException conflict = assertThrows(
                ConflictException.class,
                () -> service.save("owner", AiProvider.DEEPSEEK, "personal-secret")
        );
        assertFalse(conflict.getMessage().contains("personal-secret"));
    }
}
