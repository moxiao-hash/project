package com.moxiao.studypilot.notification.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationJpaRepository extends JpaRepository<NotificationEntity, String> {

    Optional<NotificationEntity> findByIdAndOwnerId(String id, String ownerId);

    List<NotificationEntity> findAllByOwnerIdOrderByCreatedAtDesc(String ownerId);

    long countByOwnerIdAndReadFalse(String ownerId);
}
