package com.moxiao.studypilot.material.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "web_search_sessions")
public class WebSearchSessionEntity {

    @Id
    private String id;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(nullable = false, length = 500)
    private String query;

    @Column(name = "provider_request_id", length = 100)
    private String providerRequestId;

    @Column(name = "searched_at", nullable = false)
    private Instant searchedAt;

    protected WebSearchSessionEntity() {
    }

    public WebSearchSessionEntity(
            String id,
            String ownerId,
            String query,
            String providerRequestId,
            Instant searchedAt
    ) {
        this.id = id;
        this.ownerId = ownerId;
        this.query = query;
        this.providerRequestId = providerRequestId;
        this.searchedAt = searchedAt;
    }

    public String getId() {
        return id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getQuery() {
        return query;
    }

    public String getProviderRequestId() {
        return providerRequestId;
    }

    public Instant getSearchedAt() {
        return searchedAt;
    }
}
