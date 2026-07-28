package com.moxiao.studypilot.aicredential.infrastructure;

import com.moxiao.studypilot.aicredential.domain.AiProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(
        name = "ai_credentials",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ai_credentials_owner_provider",
                columnNames = {"owner_id", "provider"}
        )
)
public class AiCredentialEntity {

    @Id
    private String id;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AiProvider provider;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String ciphertext;

    @Column(nullable = false, length = 64)
    private String iv;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AiCredentialEntity() {}

    public AiCredentialEntity(
            String id,
            String ownerId,
            AiProvider provider,
            String ciphertext,
            String iv,
            Instant now
    ) {
        this.id = id;
        this.ownerId = ownerId;
        this.provider = provider;
        this.ciphertext = ciphertext;
        this.iv = iv;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void replace(String ciphertext, String iv, Instant now) {
        this.ciphertext = ciphertext;
        this.iv = iv;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public AiProvider getProvider() { return provider; }
    public String getCiphertext() { return ciphertext; }
    public String getIv() { return iv; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
