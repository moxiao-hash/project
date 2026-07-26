package com.moxiao.studypilot.material.infrastructure;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface WebSearchResultJpaRepository
        extends JpaRepository<WebSearchResultEntity, String> {

    List<WebSearchResultEntity> findAllBySearchIdOrderByScoreDesc(String searchId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select result from WebSearchResultEntity result
            where result.id = :id and result.ownerId = :ownerId
            """)
    Optional<WebSearchResultEntity> findOwnedForUpdate(String id, String ownerId);
}
