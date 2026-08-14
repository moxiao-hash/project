package com.moxiao.studypilot.assessment.infrastructure;

import com.moxiao.studypilot.assessment.domain.QuestionType;
import com.moxiao.studypilot.assessment.domain.CodingKind;
import com.moxiao.studypilot.assessment.domain.Difficulty;
import jakarta.persistence.Embedded;
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Difficulty difficulty = Difficulty.EASY;

    @Enumerated(EnumType.STRING)
    @Column(name = "coding_kind", length = 30)
    private CodingKind codingKind;

    @Column(length = 30)
    private String language;

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

    @Column(name = "starter_code", columnDefinition = "TEXT")
    private String starterCode;

    @Column(name = "rubric_json", columnDefinition = "TEXT")
    private String rubricJson;

    @Column(name = "reference_answer", columnDefinition = "TEXT")
    private String referenceAnswer;

    @Column(name = "question_signature", length = 64)
    private String questionSignature;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "quiz_question_sources",
            joinColumns = @JoinColumn(name = "question_id"))
    private List<QuestionSourceEmbeddable> sources = new ArrayList<>();

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
        this(
                id, quizId, position, type, Difficulty.EASY, null, null,
                knowledgePoint, questionText, options, correctAnswers, explanation,
                null, null, null, List.of(), null
        );
    }

    public QuestionEntity(
            String id,
            String quizId,
            int position,
            QuestionType type,
            Difficulty difficulty,
            CodingKind codingKind,
            String language,
            String knowledgePoint,
            String questionText,
            List<String> options,
            Set<String> correctAnswers,
            String explanation,
            String starterCode,
            String rubricJson,
            String referenceAnswer,
            List<QuestionSourceEmbeddable> sources,
            String questionSignature
    ) {
        this.id = id;
        this.quizId = quizId;
        this.position = position;
        this.type = type;
        this.difficulty = difficulty == null ? Difficulty.EASY : difficulty;
        this.codingKind = codingKind;
        this.language = language;
        this.knowledgePoint = knowledgePoint;
        this.questionText = questionText;
        this.options = options == null ? new ArrayList<>() : new ArrayList<>(options);
        this.correctAnswers = new LinkedHashSet<>(correctAnswers);
        this.explanation = explanation;
        this.starterCode = starterCode;
        this.rubricJson = rubricJson;
        this.referenceAnswer = referenceAnswer;
        this.sources = new ArrayList<>(sources);
        this.questionSignature = questionSignature;
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

    public Difficulty getDifficulty() { return difficulty; }
    public CodingKind getCodingKind() { return codingKind; }
    public String getLanguage() { return language; }
    public String getStarterCode() { return starterCode; }
    public String getRubricJson() { return rubricJson; }
    public String getReferenceAnswer() { return referenceAnswer; }
    public List<QuestionSourceEmbeddable> getSources() { return List.copyOf(sources); }
    public String getQuestionSignature() { return questionSignature; }
}
