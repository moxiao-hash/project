package com.moxiao.studypilot.dashboard.application;

import com.moxiao.studypilot.assessment.infrastructure.MasteryJpaRepository;
import com.moxiao.studypilot.dashboard.api.DashboardResponse;
import com.moxiao.studypilot.learning.domain.LearningGoalRepository;
import com.moxiao.studypilot.learning.domain.LearningTaskStatus;
import com.moxiao.studypilot.learning.infrastructure.LearningTaskJpaRepository;
import com.moxiao.studypilot.material.domain.MaterialProcessingStatus;
import com.moxiao.studypilot.material.infrastructure.MaterialJpaRepository;
import com.moxiao.studypilot.notification.infrastructure.NotificationJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
public class DashboardService {

    private static final double LOW_MASTERY_THRESHOLD = 60.0;

    private final LearningGoalRepository goalRepository;
    private final LearningTaskJpaRepository taskRepository;
    private final MaterialJpaRepository materialRepository;
    private final MasteryJpaRepository masteryRepository;
    private final NotificationJpaRepository notificationRepository;

    public DashboardService(
            LearningGoalRepository goalRepository,
            LearningTaskJpaRepository taskRepository,
            MaterialJpaRepository materialRepository,
            MasteryJpaRepository masteryRepository,
            NotificationJpaRepository notificationRepository
    ) {
        this.goalRepository = goalRepository;
        this.taskRepository = taskRepository;
        this.materialRepository = materialRepository;
        this.masteryRepository = masteryRepository;
        this.notificationRepository = notificationRepository;
    }

    @Transactional(readOnly = true)
    public DashboardResponse get(String ownerId) {
        LocalDate today = LocalDate.now();
        return new DashboardResponse(
                goalRepository.countByOwnerId(ownerId),
                taskRepository.countByOwnerIdAndScheduledDate(ownerId, today),
                taskRepository.countByOwnerIdAndScheduledDateAndStatus(
                        ownerId,
                        today,
                        LearningTaskStatus.COMPLETED
                ),
                materialRepository.countByOwnerIdAndProcessingStatus(
                        ownerId,
                        MaterialProcessingStatus.PENDING
                ),
                masteryRepository.countByOwnerIdAndScoreLessThan(
                        ownerId,
                        LOW_MASTERY_THRESHOLD
                ),
                notificationRepository.countByOwnerIdAndReadFalse(ownerId)
        );
    }
}
