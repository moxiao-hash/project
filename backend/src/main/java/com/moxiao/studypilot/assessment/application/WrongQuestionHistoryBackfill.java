package com.moxiao.studypilot.assessment.application;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Replays terminal historical attempts after Flyway has created the wrong-question tables.
 * Unique attempt/question events make every restart safe and idempotent.
 */
@Component
public class WrongQuestionHistoryBackfill {
    private final QuizService quizService;

    public WrongQuestionHistoryBackfill(QuizService quizService) {
        this.quizService = quizService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void backfill() {
        quizService.backfillWrongQuestions();
    }
}
