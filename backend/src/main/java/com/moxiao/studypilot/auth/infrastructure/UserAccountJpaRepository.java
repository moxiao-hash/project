package com.moxiao.studypilot.auth.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface UserAccountJpaRepository extends JpaRepository<UserAccountEntity, String> {

    Optional<UserAccountEntity> findByEmail(String email);

    boolean existsByEmail(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from UserAccountEntity user where user.id = :id")
    Optional<UserAccountEntity> findByIdForUpdate(@Param("id") String id);
}
