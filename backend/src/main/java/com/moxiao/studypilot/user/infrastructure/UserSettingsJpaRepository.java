package com.moxiao.studypilot.user.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserSettingsJpaRepository extends JpaRepository<UserSettingsEntity, String> {
}
