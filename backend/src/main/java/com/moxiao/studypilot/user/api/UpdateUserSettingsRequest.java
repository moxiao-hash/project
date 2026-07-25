package com.moxiao.studypilot.user.api;

import com.moxiao.studypilot.user.domain.PrivacyLevel;
import com.moxiao.studypilot.user.domain.WeekendPreference;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record UpdateUserSettingsRequest(
        @NotBlank String timeZone,
        @Min(15) @Max(720) int dailyStudyLimitMinutes,
        @NotNull WeekendPreference weekendPreference,
        @NotNull PrivacyLevel defaultPrivacyLevel,
        @NotNull List<@Valid AvailabilitySlotRequest> weeklyAvailability
) {
}
