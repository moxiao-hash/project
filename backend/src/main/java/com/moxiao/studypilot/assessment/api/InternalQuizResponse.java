package com.moxiao.studypilot.assessment.api;

import com.moxiao.studypilot.assessment.infrastructure.QuestionEntity;
import com.moxiao.studypilot.assessment.infrastructure.QuizEntity;

import java.util.List;
import java.util.Set;

public record InternalQuizResponse(
        String id,
        String title,
        List<InternalQuestionResponse> questions
) {
    public static InternalQuizResponse from(
            QuizEntity quiz,
            List<QuestionEntity> questions
    ) {
        return new InternalQuizResponse(
                quiz.getId(),
                quiz.getTitle(),
                questions.stream()
                        .map(question -> new InternalQuestionResponse(
                                question.getId(),
                                question.getCorrectAnswers(),
                                question.getReferenceAnswer()
                        ))
                        .toList()
        );
    }

    public record InternalQuestionResponse(
            String id,
            Set<String> correctAnswers,
            String referenceAnswer
    ) {
    }
}
