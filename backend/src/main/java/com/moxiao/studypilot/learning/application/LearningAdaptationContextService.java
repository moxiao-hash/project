package com.moxiao.studypilot.learning.application;

import com.moxiao.studypilot.learning.api.AdaptationSignalResponse;
import com.moxiao.studypilot.learning.api.InternalAdaptationContextResponse;
import com.moxiao.studypilot.learning.api.LearningPlanResponse;
import com.moxiao.studypilot.learning.api.LearningTaskResponse;
import com.moxiao.studypilot.learning.domain.AdaptationSignalType;
import com.moxiao.studypilot.learning.domain.LearningPlanStatus;
import com.moxiao.studypilot.learning.domain.LearningTaskStatus;
import com.moxiao.studypilot.learning.infrastructure.LearningPlanEntity;
import com.moxiao.studypilot.learning.infrastructure.LearningPlanJpaRepository;
import com.moxiao.studypilot.learning.infrastructure.LearningTaskEntity;
import com.moxiao.studypilot.learning.infrastructure.LearningTaskJpaRepository;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import com.moxiao.studypilot.user.infrastructure.UserSettingsEntity;
import com.moxiao.studypilot.user.infrastructure.UserSettingsJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class LearningAdaptationContextService {

    private static final double TIME_DEVIATION_THRESHOLD = 0.30;
    private static final int MIN_DURATION_SAMPLES = 3;
    private static final int DEFAULT_DAILY_STUDY_LIMIT_MINUTES = 120;

    private final LearningPlanJpaRepository planRepository;
    private final LearningTaskJpaRepository taskRepository;
    private final UserSettingsJpaRepository settingsRepository;

    public LearningAdaptationContextService(
            LearningPlanJpaRepository planRepository,
            LearningTaskJpaRepository taskRepository,
            UserSettingsJpaRepository settingsRepository
    ) {
        this.planRepository = planRepository;
        this.taskRepository = taskRepository;
        this.settingsRepository = settingsRepository;
    }

    @Transactional(readOnly = true)
    public InternalAdaptationContextResponse get(
            String ownerId,
            LocalDate analysisDate,
            int windowDays
    ) {
        LearningPlanEntity plan = planRepository
                .findAllByOwnerIdOrderByCreatedAtDesc(ownerId)
                .stream()
                .filter(candidate -> candidate.getStatus() == LearningPlanStatus.CONFIRMED)
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("没有可调整的已确认计划"));
        int dailyStudyLimitMinutes = settingsRepository.findById(ownerId)
                .map(UserSettingsEntity::getDailyStudyLimitMinutes)
                .orElse(DEFAULT_DAILY_STUDY_LIMIT_MINUTES);
        List<LearningTaskEntity> allTasks =
                taskRepository.findAllByPlanIdOrderByScheduledDateAscCreatedAtAsc(plan.getId());
        LocalDate windowStart = analysisDate.minusDays(windowDays - 1L);
        List<LearningTaskEntity> windowTasks = allTasks.stream()
                .filter(task -> !task.getScheduledDate().isBefore(windowStart))
                .filter(task -> !task.getScheduledDate().isAfter(analysisDate))
                .toList();

        return new InternalAdaptationContextResponse(
                ownerId,
                analysisDate,
                windowDays,
                dailyStudyLimitMinutes,
                LearningPlanResponse.from(plan),
                allTasks.stream().map(LearningTaskResponse::from).toList(),
                detectSignals(windowTasks, analysisDate)
        );
    }

    private List<AdaptationSignalResponse> detectSignals(
            List<LearningTaskEntity> tasks,
            LocalDate analysisDate
    ) {
        List<AdaptationSignalResponse> signals = new ArrayList<>();
        int overdueCount = (int) tasks.stream()
                .filter(task -> task.getStatus() == LearningTaskStatus.TODO)
                .filter(task -> task.getScheduledDate().isBefore(analysisDate))
                .count();
        if (overdueCount > 0) {
            signals.add(new AdaptationSignalResponse(
                    AdaptationSignalType.OVERDUE_TASKS,
                    overdueCount,
                    null
            ));
        }

        int consecutiveSkips = countRecentConsecutiveSkips(tasks);
        if (consecutiveSkips >= 2) {
            signals.add(new AdaptationSignalResponse(
                    AdaptationSignalType.CONSECUTIVE_SKIPS,
                    consecutiveSkips,
                    null
            ));
        }

        List<LearningTaskEntity> durationSamples = tasks.stream()
                .filter(task -> task.getStatus() == LearningTaskStatus.COMPLETED)
                .filter(task -> task.getActualMinutes() != null)
                .toList();
        if (durationSamples.size() >= MIN_DURATION_SAMPLES) {
            int estimated = durationSamples.stream()
                    .mapToInt(LearningTaskEntity::getEstimatedMinutes)
                    .sum();
            int actual = durationSamples.stream()
                    .mapToInt(LearningTaskEntity::getActualMinutes)
                    .sum();
            double ratio = Math.abs(actual - estimated) / (double) estimated;
            if (ratio > TIME_DEVIATION_THRESHOLD) {
                signals.add(new AdaptationSignalResponse(
                        AdaptationSignalType.TIME_ESTIMATE_BIAS,
                        durationSamples.size(),
                        roundRatio(ratio)
                ));
            }
        }
        return List.copyOf(signals);
    }

    private int countRecentConsecutiveSkips(List<LearningTaskEntity> tasks) {
        List<LearningTaskEntity> terminalTasks = tasks.stream()
                .filter(task -> task.getStatus() == LearningTaskStatus.COMPLETED
                        || task.getStatus() == LearningTaskStatus.SKIPPED)
                .sorted(Comparator.comparing(LearningTaskEntity::getScheduledDate).reversed())
                .toList();
        int count = 0;
        for (LearningTaskEntity task : terminalTasks) {
            if (task.getStatus() != LearningTaskStatus.SKIPPED) {
                break;
            }
            count++;
        }
        return count;
    }

    private double roundRatio(double value) {
        return BigDecimal.valueOf(value)
                .setScale(4, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
