package com.moxiao.studypilot.user.application;

import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import com.moxiao.studypilot.user.api.UpdateUserSettingsRequest;
import com.moxiao.studypilot.user.infrastructure.AvailabilitySlotEmbeddable;
import com.moxiao.studypilot.user.infrastructure.UserSettingsEntity;
import com.moxiao.studypilot.user.infrastructure.UserSettingsJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;

@Service
public class UserSettingsService {

    private final UserSettingsJpaRepository repository;

    public UserSettingsService(UserSettingsJpaRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public UserSettingsEntity save(String userId, UpdateUserSettingsRequest request) {
        ZoneId.of(request.timeZone());
        UserSettingsEntity settings = new UserSettingsEntity(
                userId,
                request.timeZone(),
                request.dailyStudyLimitMinutes(),
                request.weekendPreference(),
                request.defaultPrivacyLevel(),
                request.weeklyAvailability().stream()
                        .map(slot -> new AvailabilitySlotEmbeddable(
                                slot.dayOfWeek(),
                                slot.startTime(),
                                slot.endTime()
                        ))
                        .toList(),
                Instant.now()
        );
        return repository.save(settings);
    }

    @Transactional(readOnly = true)
    public UserSettingsEntity get(String userId) {
        return repository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("尚未配置个人学习设置"));
    }
}
