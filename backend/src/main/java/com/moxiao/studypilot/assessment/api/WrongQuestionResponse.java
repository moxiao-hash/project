package com.moxiao.studypilot.assessment.api;

import com.moxiao.studypilot.assessment.domain.CodingKind;
import com.moxiao.studypilot.assessment.domain.Difficulty;
import com.moxiao.studypilot.assessment.domain.QuestionType;
import com.moxiao.studypilot.assessment.domain.WrongQuestionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public record WrongQuestionResponse(
        String id,
        WrongQuestionStatus status,
        String chapterKey,
        String chapterTitle,
        QuestionType type,
        Difficulty difficulty,
        CodingKind codingKind,
        String language,
        String knowledgePoint,
        String questionText,
        List<String> options,
        Set<String> latestSelectedAnswers,
        String latestCodeAnswer,
        Set<String> correctAnswers,
        String referenceAnswer,
        String explanation,
        List<QuizResponse.SourceResponse> sources,
        int wrongCount,
        int redoCount,
        Instant firstWrongAt,
        Instant lastWrongAt,
        Instant masteredAt
) {
}
