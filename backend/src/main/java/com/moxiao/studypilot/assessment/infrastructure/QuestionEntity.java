package com.moxiao.studypilot.assessment.infrastructure;

import com.moxiao.studypilot.assessment.domain.QuestionType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "quiz_questions")
public class QuestionEntity {

    @Id
    private String id;

    @Column(name = "quiz_id", nullable = false)
    private String quizId;

    @Column(name = "question_position", nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private QuestionType type;

    @Column(name = "knowledge_point", nullable = false, length = 180)
    private String knowledgePoint;

    @Column(name = "question_text", nullable = false, columnDefinition = "TEXT")
    private String questionText;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "question_options", joinColumns = @JoinColumn(name = "question_id"))
    @OrderColumn(name = "position")
    @Column(name = "option_text", nullable = false, length = 500)
    private List<String> options = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "question_correct_answers",
            joinColumns = @JoinColumn(name = "question_id")
    )
    @Column(name = "answer_text", nullable = false, length = 500)
    private Set<String> correctAnswers = new LinkedHashSet<>();

    @Column(nullable = false, columnDefinition = "TEXT")
    private String explanation;

    protected QuestionEntity() {
    }

    public QuestionEntity(
            String id,
            String quizId,
            int position,
            QuestionType type,
            String knowledgePoint,
            String questionText,
            List<String> options,
            Set<String> correctAnswers,
            String explanation
    ) {
        this.id = id;
        this.quizId = quizId;
        this.position = position;
        this.type = type;
        this.knowledgePoint = knowledgePoint;
        this.questionText = questionText;
        this.options = new ArrayList<>(options);
        this.correctAnswers = new LinkedHashSet<>(correctAnswers);
        this.explanation = explanation;
    }

    public String getId() {
        return id;
    }

    public QuestionType getType() {
        return type;
    }

    public String getKnowledgePoint() {
        return knowledgePoint;
    }

    public String getQuestionText() {
        return questionText;
    }

    public List<String> getOptions() {
        return List.copyOf(options);
    }

    public Set<String> getCorrectAnswers() {
        return Set.copyOf(correctAnswers);
    }

    public String getExplanation() {
        return explanation;
    }
}
