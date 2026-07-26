package com.moxiao.studypilot.material.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WebSearchSessionJpaRepository
        extends JpaRepository<WebSearchSessionEntity, String> {
}
