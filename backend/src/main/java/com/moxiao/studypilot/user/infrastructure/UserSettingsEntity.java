package com.moxiao.studypilot.user.infrastructure;

import com.moxiao.studypilot.user.domain.PrivacyLevel;
import com.moxiao.studypilot.user.domain.WeekendPreference;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "user_settings")
public class UserSettingsEntity {

    @Id
    @Column(name = "user_id")
    private String userId;

    @Column(name = "time_zone", nullable = false, length = 50)
    private String timeZone;

    @Column(name = "daily_study_limit_minutes", nullable = false)
    private int dailyStudyLimitMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "weekend_preference", nullable = false, length = 10)
    private WeekendPreference weekendPreference;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_privacy_level", nullable = false, length = 20)
    private PrivacyLevel defaultPrivacyLevel;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "weekly_availability",
            joinColumns = @JoinColumn(name = "user_id")
    )
    private List<AvailabilitySlotEmbeddable> weeklyAvailability = new ArrayList<>();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserSettingsEntity() {
    }

    public UserSettingsEntity(
            String userId,
            String timeZone,
            int dailyStudyLimitMinutes,
            WeekendPreference weekendPreference,
            PrivacyLevel defaultPrivacyLevel,
            List<AvailabilitySlotEmbeddable> weeklyAvailability,
            Instant updatedAt
    ) {
        this.userId = userId;
        this.timeZone = timeZone;
        this.dailyStudyLimitMinutes = dailyStudyLimitMinutes;
        this.weekendPreference = weekendPreference;
        this.defaultPrivacyLevel = defaultPrivacyLevel;
        this.weeklyAvailability = new ArrayList<>(weeklyAvailability);
        this.updatedAt = updatedAt;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public int getDailyStudyLimitMinutes() {
        return dailyStudyLimitMinutes;
    }

    public WeekendPreference getWeekendPreference() {
        return weekendPreference;
    }

    public PrivacyLevel getDefaultPrivacyLevel() {
        return defaultPrivacyLevel;
    }

    public List<AvailabilitySlotEmbeddable> getWeeklyAvailability() {
        return List.copyOf(weeklyAvailability);
    }
}
