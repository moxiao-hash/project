package com.moxiao.studypilot.agent.infrastructure;

import com.moxiao.studypilot.agent.domain.AgentScope;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "agent_grants")
public class AgentGrantEntity {

    @Id
    private String id;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_grant_scopes", joinColumns = @JoinColumn(name = "grant_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 40)
    private Set<AgentScope> scopes = new LinkedHashSet<>();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AgentGrantEntity() {
    }

    public AgentGrantEntity(
            String id,
            String ownerId,
            Set<AgentScope> scopes,
            Instant expiresAt,
            Instant createdAt
    ) {
        this.id = id;
        this.ownerId = ownerId;
        this.scopes = new LinkedHashSet<>(scopes);
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public boolean isActiveAt(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public boolean includes(AgentScope scope, Instant now) {
        return isActiveAt(now) && scopes.contains(scope);
    }

    public String getId() {
        return id;
    }

    public Set<AgentScope> getScopes() {
        return Set.copyOf(scopes);
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
