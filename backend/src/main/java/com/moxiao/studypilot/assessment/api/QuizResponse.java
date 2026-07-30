package com.moxiao.studypilot.assessment.api;

import com.moxiao.studypilot.assessment.domain.QuestionType;
import com.moxiao.studypilot.assessment.infrastructure.QuestionEntity;
import com.moxiao.studypilot.assessment.infrastructure.QuizEntity;

import java.util.List;

public record QuizResponse(
        String id,
        String materialId,
        String taskId,
        String lessonId,
        String title,
        String modelName,
        List<QuestionResponse> questions
) {
    public static QuizResponse from(QuizEntity quiz, List<QuestionEntity> questions) {
        return new QuizResponse(
                quiz.getId(),
                quiz.getMaterialId(),
                quiz.getTaskId(),
                quiz.getLessonId(),
                quiz.getTitle(),
                quiz.getModelName(),
                questions.stream().map(QuestionResponse::from).toList()
        );
    }

    public record QuestionResponse(
            String id,
            QuestionType type,
            com.moxiao.studypilot.assessment.domain.Difficulty difficulty,
            com.moxiao.studypilot.assessment.domain.CodingKind codingKind,
            String language,
            String knowledgePoint,
            String questionText,
            List<String> options,
            String starterCode,
            List<SourceResponse> sources
    ) {
        static QuestionResponse from(QuestionEntity question) {
            return new QuestionResponse(
                    question.getId(),
                    question.getType(),
                    question.getDifficulty(),
                    question.getCodingKind(),
                    question.getLanguage(),
                    question.getKnowledgePoint(),
                    question.getQuestionText(),
                    question.getOptions(),
                    question.getStarterCode(),
                    question.getSources().stream().map(SourceResponse::from).toList()
            );
        }
    }

    public record SourceResponse(
            String sourceType,
            String materialId,
            String webResultId,
            String title,
            String locator,
            String snippet
    ) {
        static SourceResponse from(
                com.moxiao.studypilot.assessment.infrastructure.QuestionSourceEmbeddable source
        ) {
            return new SourceResponse(
                    source.getSourceType(), source.getMaterialId(), source.getWebResultId(),
                    source.getTitle(), source.getLocator(), source.getSnippet()
            );
        }
    }
}
