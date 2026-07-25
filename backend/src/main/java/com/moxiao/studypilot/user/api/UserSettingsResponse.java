package com.moxiao.studypilot.user.api;

import com.moxiao.studypilot.user.domain.PrivacyLevel;
import com.moxiao.studypilot.user.domain.WeekendPreference;
import com.moxiao.studypilot.user.infrastructure.UserSettingsEntity;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

public record UserSettingsResponse(
        String timeZone,
        int dailyStudyLimitMinutes,
        WeekendPreference weekendPreference,
        PrivacyLevel defaultPrivacyLevel,
        List<AvailabilitySlotResponse> weeklyAvailability
) {
    public static UserSettingsResponse from(UserSettingsEntity entity) {
        return new UserSettingsResponse(
                entity.getTimeZone(),
                entity.getDailyStudyLimitMinutes(),
                entity.getWeekendPreference(),
                entity.getDefaultPrivacyLevel(),
                entity.getWeeklyAvailability().stream()
                        .map(slot -> new AvailabilitySlotResponse(
                                slot.getDayOfWeek(),
                                slot.getStartTime(),
                                slot.getEndTime()
                        ))
                        .toList()
        );
    }

    public record AvailabilitySlotResponse(
            DayOfWeek dayOfWeek,
            LocalTime startTime,
            LocalTime endTime
    ) {
    }
}
