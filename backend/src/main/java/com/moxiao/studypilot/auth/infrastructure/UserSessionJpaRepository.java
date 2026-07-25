package com.moxiao.studypilot.auth.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserSessionJpaRepository extends JpaRepository<UserSessionEntity, String> {
}
