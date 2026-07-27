package com.moxiao.studypilot.assessment.application;

import com.moxiao.studypilot.assessment.api.CreateQuizRequest;
import com.moxiao.studypilot.assessment.api.QuizAttemptResponse;
import com.moxiao.studypilot.assessment.api.SubmitQuizAttemptRequest;
import com.moxiao.studypilot.assessment.infrastructure.MasteryEntity;
import com.moxiao.studypilot.assessment.infrastructure.MasteryJpaRepository;
import com.moxiao.studypilot.assessment.infrastructure.QuestionEntity;
import com.moxiao.studypilot.assessment.infrastructure.QuestionJpaRepository;
import com.moxiao.studypilot.assessment.infrastructure.QuizAttemptEntity;
import com.moxiao.studypilot.assessment.infrastructure.QuizAttemptJpaRepository;
import com.moxiao.studypilot.assessment.infrastructure.QuizEntity;
import com.moxiao.studypilot.assessment.infrastructure.QuizJpaRepository;
import com.moxiao.studypilot.assessment.infrastructure.QuestionSourceEmbeddable;
import com.moxiao.studypilot.auth.infrastructure.UserAccountJpaRepository;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

@Service
public class QuizService {

    private final UserAccountJpaRepository userRepository;
    private final QuizJpaRepository quizRepository;
    private final QuestionJpaRepository questionRepository;
    private final QuizAttemptJpaRepository attemptRepository;
    private final MasteryJpaRepository masteryRepository;
    private final ObjectMapper objectMapper;

    public QuizService(
            UserAccountJpaRepository userRepository,
            QuizJpaRepository quizRepository,
            QuestionJpaRepository questionRepository,
            QuizAttemptJpaRepository attemptRepository,
            MasteryJpaRepository masteryRepository,
            ObjectMapper objectMapper
    ) {
        this.userRepository = userRepository;
        this.quizRepository = quizRepository;
        this.questionRepository = questionRepository;
        this.attemptRepository = attemptRepository;
        this.masteryRepository = masteryRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public QuizBundle create(CreateQuizRequest request) {
        if (!userRepository.existsById(request.ownerId())) {
            throw new ResourceNotFoundException("用户不存在");
        }
        String quizId = UUID.randomUUID().toString();
        QuizEntity quiz = quizRepository.save(new QuizEntity(
                quizId,
                request.ownerId(),
                request.materialId(),
                request.taskId(),
                request.title().trim(),
                request.modelName(),
                Instant.now()
        ));
        List<QuestionEntity> questions = new ArrayList<>();
        for (int index = 0; index < request.questions().size(); index++) {
            CreateQuizRequest.QuestionInput input = request.questions().get(index);
            validateQuestion(input);
            questions.add(new QuestionEntity(
                    UUID.randomUUID().toString(),
                    quizId,
                    index,
                    input.type(),
                    input.difficulty(),
                    input.codingKind(),
                    input.language(),
                    input.knowledgePoint(),
                    input.questionText(),
                    input.options(),
                    input.correctAnswers(),
                    input.explanation(),
                    input.starterCode(),
                    input.rubric() == null ? null : objectMapper.writeValueAsString(input.rubric()),
                    input.referenceAnswer(),
                    (input.sources() == null ? List.<CreateQuizRequest.SourceInput>of()
                            : input.sources()).stream()
                            .map(source -> new QuestionSourceEmbeddable(
                                    source.sourceType(),
                                    source.materialId(),
                                    source.webResultId(),
                                    source.title(),
                                    source.locator(),
                                    source.snippet()
                            ))
                            .toList()
            ));
        }
        return new QuizBundle(quiz, questionRepository.saveAll(questions));
    }

    private void validateQuestion(CreateQuizRequest.QuestionInput input) {
        if (input.type() == com.moxiao.studypilot.assessment.domain.QuestionType.CODING) {
            if (input.codingKind() == null || input.language() == null
                    || input.starterCode() == null || input.rubric() == null
                    || input.referenceAnswer() == null) {
                throw new IllegalArgumentException("编程题必须提供类型、语言、代码、Rubric 和参考答案");
            }
            int total = input.rubric().correctness() + input.rubric().completeness()
                    + input.rubric().edgeCases() + input.rubric().clarityEfficiency();
            if (total != 100) {
                throw new IllegalArgumentException("编程题 Rubric 权重之和必须为 100");
            }
        } else if (input.options() == null || input.options().size() < 2) {
            throw new IllegalArgumentException("选择题至少需要两个选项");
        }
    }

    @Transactional(readOnly = true)
    public QuizBundle get(String ownerId, String quizId) {
        QuizEntity quiz = quizRepository.findByIdAndOwnerId(quizId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("测验不存在"));
        return new QuizBundle(
                quiz,
                questionRepository.findAllByQuizIdOrderByPosition(quizId)
        );
    }

    @Transactional
    public QuizAttemptResponse submit(
            String ownerId,
            String quizId,
            SubmitQuizAttemptRequest request
    ) {
        QuizBundle bundle = get(ownerId, quizId);
        Map<String, Set<String>> submitted = new HashMap<>();
        request.answers().forEach(answer ->
                submitted.put(answer.questionId(), answer.selectedAnswers())
        );
        if (submitted.size() != bundle.questions().size()) {
            throw new IllegalArgumentException("必须回答测验中的全部题目");
        }

        Instant now = Instant.now();
        int correctCount = 0;
        List<QuizAttemptResponse.QuestionResult> results = new ArrayList<>();
        for (QuestionEntity question : bundle.questions()) {
            Set<String> selected = submitted.get(question.getId());
            if (selected == null) {
                throw new IllegalArgumentException("提交的题目与测验不匹配");
            }
            boolean correct = question.getCorrectAnswers().equals(selected);
            if (correct) {
                correctCount++;
            }
            recordMastery(
                    ownerId,
                    question.getKnowledgePoint(),
                    correct ? 100.0 : 0.0,
                    now
            );
            results.add(new QuizAttemptResponse.QuestionResult(
                    question.getId(),
                    correct,
                    question.getKnowledgePoint(),
                    question.getExplanation()
            ));
        }
        double score = correctCount * 100.0 / bundle.questions().size();
        String attemptId = UUID.randomUUID().toString();
        attemptRepository.save(new QuizAttemptEntity(
                attemptId,
                quizId,
                ownerId,
                score,
                "{\"answeredQuestionCount\":" + submitted.size() + "}",
                now
        ));
        return new QuizAttemptResponse(attemptId, score, results);
    }

    @Transactional(readOnly = true)
    public List<MasteryEntity> listMastery(String ownerId) {
        return masteryRepository.findAllByOwnerIdOrderByScoreAsc(ownerId);
    }

    private void recordMastery(
            String ownerId,
            String knowledgePoint,
            double score,
            Instant now
    ) {
        MasteryEntity mastery = masteryRepository
                .findByOwnerIdAndKnowledgePoint(ownerId, knowledgePoint)
                .orElseGet(() -> new MasteryEntity(
                        UUID.randomUUID().toString(),
                        ownerId,
                        knowledgePoint,
                        score,
                        now
                ));
        if (mastery.getAttemptCount() > 0
                && masteryRepository.findByOwnerIdAndKnowledgePoint(ownerId, knowledgePoint).isPresent()) {
            mastery.record(score, now);
        }
        masteryRepository.save(mastery);
    }

    public record QuizBundle(QuizEntity quiz, List<QuestionEntity> questions) {
    }
}
