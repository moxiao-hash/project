package com.moxiao.studypilot.assessment.api;

import com.moxiao.studypilot.assessment.domain.QuestionType;
import com.moxiao.studypilot.assessment.infrastructure.QuestionEntity;
import com.moxiao.studypilot.assessment.infrastructure.QuizEntity;

import java.util.List;

public record QuizResponse(
        String id,
        String materialId,
        String title,
        String modelName,
        List<QuestionResponse> questions
) {
    public static QuizResponse from(QuizEntity quiz, List<QuestionEntity> questions) {
        return new QuizResponse(
                quiz.getId(),
                quiz.getMaterialId(),
                quiz.getTitle(),
                quiz.getModelName(),
                questions.stream().map(QuestionResponse::from).toList()
        );
    }

    public record QuestionResponse(
            String id,
            QuestionType type,
            String knowledgePoint,
            String questionText,
            List<String> options
    ) {
        static QuestionResponse from(QuestionEntity question) {
            return new QuestionResponse(
                    question.getId(),
                    question.getType(),
                    question.getKnowledgePoint(),
                    question.getQuestionText(),
                    question.getOptions()
            );
        }
    }
}
