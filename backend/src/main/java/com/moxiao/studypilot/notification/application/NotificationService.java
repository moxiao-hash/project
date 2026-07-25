package com.moxiao.studypilot.notification.application;

import com.moxiao.studypilot.auth.infrastructure.UserAccountJpaRepository;
import com.moxiao.studypilot.notification.api.CreateNotificationRequest;
import com.moxiao.studypilot.notification.infrastructure.NotificationEntity;
import com.moxiao.studypilot.notification.infrastructure.NotificationJpaRepository;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class NotificationService {

    private final UserAccountJpaRepository userRepository;
    private final NotificationJpaRepository repository;

    public NotificationService(
            UserAccountJpaRepository userRepository,
            NotificationJpaRepository repository
    ) {
        this.userRepository = userRepository;
        this.repository = repository;
    }

    @Transactional
    public NotificationEntity create(CreateNotificationRequest request) {
        if (!userRepository.existsById(request.ownerId())) {
            throw new ResourceNotFoundException("用户不存在");
        }
        return repository.save(new NotificationEntity(
                UUID.randomUUID().toString(),
                request.ownerId(),
                request.type(),
                request.title().trim(),
                request.content().trim(),
                Instant.now()
        ));
    }

    @Transactional(readOnly = true)
    public List<NotificationEntity> list(String ownerId) {
        return repository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId);
    }

    @Transactional
    public NotificationEntity markRead(String ownerId, String notificationId) {
        NotificationEntity notification = repository.findByIdAndOwnerId(notificationId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("通知不存在"));
        notification.markRead(Instant.now());
        return notification;
    }
}
