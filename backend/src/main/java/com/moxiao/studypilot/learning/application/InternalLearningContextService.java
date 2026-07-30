package com.moxiao.studypilot.learning.application;

import com.moxiao.studypilot.assessment.api.MasteryResponse;
import com.moxiao.studypilot.assessment.application.QuizService;
import com.moxiao.studypilot.learning.api.InternalLearningContextResponse;
import com.moxiao.studypilot.learning.api.LearningGoalResponse;
import com.moxiao.studypilot.learning.api.LearningPlanResponse;
import com.moxiao.studypilot.learning.api.LearningTaskResponse;
import com.moxiao.studypilot.material.api.MaterialResponse;
import com.moxiao.studypilot.material.application.MaterialService;
import com.moxiao.studypilot.user.infrastructure.UserSettingsEntity;
import com.moxiao.studypilot.user.infrastructure.UserSettingsJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InternalLearningContextService {

    private static final String DEFAULT_TIME_ZONE = "Asia/Shanghai";

    private final CreateLearningGoalService goalService;
    private final LearningPlanService planService;
    private final LearningTaskService taskService;
    private final MaterialService materialService;
    private final QuizService quizService;
    private final UserSettingsJpaRepository settingsRepository;

    public InternalLearningContextService(
            CreateLearningGoalService goalService,
            LearningPlanService planService,
            LearningTaskService taskService,
            MaterialService materialService,
            QuizService quizService,
            UserSettingsJpaRepository settingsRepository
    ) {
        this.goalService = goalService;
        this.planService = planService;
        this.taskService = taskService;
        this.materialService = materialService;
        this.quizService = quizService;
        this.settingsRepository = settingsRepository;
    }

    @Transactional(readOnly = true)
    public InternalLearningContextResponse get(String ownerId) {
        return new InternalLearningContextResponse(
                settingsRepository.findById(ownerId)
                        .map(UserSettingsEntity::getTimeZone)
                        .orElse(DEFAULT_TIME_ZONE),
                goalService.list(ownerId).stream().map(LearningGoalResponse::from).toList(),
                planService.list(ownerId).stream().map(LearningPlanResponse::from).toList(),
                taskService.list(ownerId, null).stream().map(LearningTaskResponse::from).toList(),
                materialService.list(ownerId).stream().map(MaterialResponse::from).toList(),
                quizService.listMastery(ownerId).stream().map(MasteryResponse::from).toList()
        );
    }
}
