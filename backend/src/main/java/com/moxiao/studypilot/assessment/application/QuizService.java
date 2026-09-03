package com.moxiao.studypilot.assessment.application;

import com.moxiao.studypilot.assessment.api.CreateQuizRequest;
import com.moxiao.studypilot.assessment.api.QuizAttemptResponse;
import com.moxiao.studypilot.assessment.api.SubmitQuizAttemptRequest;
import com.moxiao.studypilot.assessment.api.SelfAssessmentRequest;
import com.moxiao.studypilot.assessment.domain.MasteryEvidenceType;
import com.moxiao.studypilot.assessment.infrastructure.MasteryEntity;
import com.moxiao.studypilot.assessment.infrastructure.MasteryEvidenceEntity;
import com.moxiao.studypilot.assessment.infrastructure.MasteryEvidenceJpaRepository;
import com.moxiao.studypilot.assessment.infrastructure.MasteryJpaRepository;
import com.moxiao.studypilot.assessment.infrastructure.QuestionEntity;
import com.moxiao.studypilot.assessment.infrastructure.QuestionJpaRepository;
import com.moxiao.studypilot.assessment.infrastructure.QuizAttemptEntity;
import com.moxiao.studypilot.assessment.infrastructure.QuizAttemptJpaRepository;
import com.moxiao.studypilot.assessment.infrastructure.CodingEvaluationJobEntity;
import com.moxiao.studypilot.assessment.infrastructure.CodingEvaluationJobJpaRepository;
import com.moxiao.studypilot.assessment.infrastructure.QuizEntity;
import com.moxiao.studypilot.assessment.infrastructure.QuizJpaRepository;
import com.moxiao.studypilot.assessment.infrastructure.QuestionSourceEmbeddable;
import com.moxiao.studypilot.auth.infrastructure.UserAccountJpaRepository;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import com.moxiao.studypilot.shared.error.ConflictException;
import com.moxiao.studypilot.assessment.domain.QuestionType;
import com.moxiao.studypilot.assessment.domain.QuizAttemptStatus;
import com.moxiao.studypilot.learning.domain.LearningTaskStatus;
import com.moxiao.studypilot.learning.infrastructure.LearningTaskJpaRepository;
import com.moxiao.studypilot.roadmap.domain.RoadmapQuizPurpose;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapStageJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodePrerequisiteJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapNodeJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapJpaRepository;
import com.moxiao.studypilot.roadmap.application.RoadmapQuizProgressService;
import com.moxiao.studypilot.roadmap.application.RoadmapStageGraduationService;
import com.moxiao.studypilot.roadmap.application.RoadmapDiagnosticService;
import com.moxiao.studypilot.course.infrastructure.LessonJpaRepository;
import com.moxiao.studypilot.course.infrastructure.LessonProgressEntity;
import com.moxiao.studypilot.course.infrastructure.LessonProgressJpaRepository;
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
    private final MasteryEvidenceJpaRepository masteryEvidenceRepository;
    private final ObjectMapper objectMapper;
    private final CodingEvaluationJobJpaRepository codingJobRepository;
    private final LearningTaskJpaRepository learningTaskRepository;
    private final ReviewTaskCandidateService reviewTaskCandidateService;
    private final LessonJpaRepository lessonRepository;
    private final LessonProgressJpaRepository lessonProgressRepository;
    private final UserRoadmapJpaRepository userRoadmapRepository;
    private final RoadmapStageJpaRepository roadmapStageRepository;
    private final UserRoadmapNodeJpaRepository userRoadmapNodeRepository;
    private final RoadmapNodeJpaRepository roadmapNodeRepository;
    private final RoadmapNodePrerequisiteJpaRepository prerequisiteRepository;
    private final RoadmapQuizProgressService roadmapQuizProgressService;
    private final RoadmapStageGraduationService roadmapStageGraduationService;
    private final RoadmapDiagnosticService roadmapDiagnosticService;
    private final WrongQuestionService wrongQuestionService;

    public QuizService(
            UserAccountJpaRepository userRepository,
            QuizJpaRepository quizRepository,
            QuestionJpaRepository questionRepository,
            QuizAttemptJpaRepository attemptRepository,
            MasteryJpaRepository masteryRepository,
            MasteryEvidenceJpaRepository masteryEvidenceRepository,
            ObjectMapper objectMapper,
            CodingEvaluationJobJpaRepository codingJobRepository,
            LearningTaskJpaRepository learningTaskRepository,
            ReviewTaskCandidateService reviewTaskCandidateService,
            LessonJpaRepository lessonRepository,
            LessonProgressJpaRepository lessonProgressRepository,
            UserRoadmapJpaRepository userRoadmapRepository,
            RoadmapStageJpaRepository roadmapStageRepository,
            UserRoadmapNodeJpaRepository userRoadmapNodeRepository,
            RoadmapNodeJpaRepository roadmapNodeRepository,
            RoadmapNodePrerequisiteJpaRepository prerequisiteRepository,
            RoadmapQuizProgressService roadmapQuizProgressService,
            RoadmapStageGraduationService roadmapStageGraduationService,
            RoadmapDiagnosticService roadmapDiagnosticService,
            WrongQuestionService wrongQuestionService
    ) {
        this.userRepository = userRepository;
        this.quizRepository = quizRepository;
        this.questionRepository = questionRepository;
        this.attemptRepository = attemptRepository;
        this.masteryRepository = masteryRepository;
        this.masteryEvidenceRepository = masteryEvidenceRepository;
        this.objectMapper = objectMapper;
        this.codingJobRepository = codingJobRepository;
        this.learningTaskRepository = learningTaskRepository;
        this.reviewTaskCandidateService = reviewTaskCandidateService;
        this.lessonRepository = lessonRepository;
        this.lessonProgressRepository = lessonProgressRepository;
        this.userRoadmapRepository = userRoadmapRepository;
        this.roadmapStageRepository = roadmapStageRepository;
        this.userRoadmapNodeRepository = userRoadmapNodeRepository;
        this.roadmapNodeRepository = roadmapNodeRepository;
        this.prerequisiteRepository = prerequisiteRepository;
        this.roadmapQuizProgressService = roadmapQuizProgressService;
        this.roadmapStageGraduationService = roadmapStageGraduationService;
        this.roadmapDiagnosticService = roadmapDiagnosticService;
        this.wrongQuestionService = wrongQuestionService;
    }

    @Transactional
    public QuizBundle create(CreateQuizRequest request) {
        if (!userRepository.existsById(request.ownerId())) {
            throw new ResourceNotFoundException("用户不存在");
        }
        if (request.taskId() != null && request.lessonId() != null) {
            throw new IllegalArgumentException("taskId 与 lessonId 只能提供一个");
        }
        boolean routeOrigin = request.roadmapNodeId() != null
                || request.userRoadmapId() != null || request.roadmapStageId() != null;
        if (request.purpose() == null && routeOrigin) {
            throw new IllegalArgumentException("路线来源必须提供明确的测验 purpose");
        }
        boolean roadmapQuiz = request.purpose() != null;
        if (roadmapQuiz) {
            validateRoadmapOrigin(request);
        }
        if (request.purpose() == RoadmapQuizPurpose.NODE && request.questions().size() != 5) {
            throw new IllegalArgumentException("节点测验必须恰好包含五题");
        }
        if (request.purpose() == RoadmapQuizPurpose.STAGE_GRADUATION
                && request.questions().size() != 10) {
            throw new IllegalArgumentException("阶段毕业测验必须恰好包含十题");
        }
        if (roadmapQuiz) {
            Set<String> signatures = new java.util.HashSet<>();
            for (CreateQuizRequest.QuestionInput question : request.questions()) {
                if (question.questionSignature() == null
                        || question.questionSignature().isBlank()
                        || !signatures.add(question.questionSignature())) {
                    throw new IllegalArgumentException("节点测验每道题必须提供唯一 questionSignature");
                }
            }
        }
        if (request.lessonId() != null
                && !lessonRepository.existsById(request.lessonId())) {
            throw new ResourceNotFoundException("课时不存在");
        }
        String quizId = UUID.randomUUID().toString();
        QuizEntity quiz = quizRepository.save(new QuizEntity(
                quizId,
                request.ownerId(),
                request.materialId(),
                request.taskId(),
                request.lessonId(),
                request.roadmapNodeId(),
                request.userRoadmapId(),
                request.userRoadmapNodeId(),
                request.roadmapStageId(),
                request.roadmapTemplateId(),
                request.purpose(),
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
                            .toList(),
                    input.questionSignature()
                    , input.points(), input.coverageNodeId(), input.practical()
            ));
        }
        List<QuestionEntity> savedQuestions = questionRepository.saveAll(questions);
        roadmapDiagnosticService.bindQuiz(quiz, savedQuestions, Instant.now());
        roadmapStageGraduationService.bindQuiz(quiz, Instant.now());
        return new QuizBundle(quiz, savedQuestions);
    }

    private void validateRoadmapOrigin(CreateQuizRequest request) {
        boolean legacyOrigin = request.materialId() != null
                || request.taskId() != null || request.lessonId() != null;
        boolean valid = switch (request.purpose()) {
            case NODE -> request.roadmapNodeId() != null
                    && request.userRoadmapId() != null
                    && request.userRoadmapNodeId() != null
                    && request.roadmapStageId() == null
                    && request.roadmapTemplateId() != null;
            case DIAGNOSTIC -> request.roadmapNodeId() == null
                    && request.userRoadmapId() != null && request.roadmapStageId() == null
                    && request.roadmapTemplateId() == null;
            case STAGE_GRADUATION -> request.roadmapNodeId() == null
                    && request.userRoadmapId() != null && request.roadmapStageId() != null
                    && request.roadmapTemplateId() != null;
        };
        if (!valid || legacyOrigin) {
            throw new IllegalArgumentException("测验 purpose 必须匹配唯一的路线来源");
        }
        if (request.purpose() == RoadmapQuizPurpose.NODE) {
            var enrollment = userRoadmapRepository.findById(request.userRoadmapId())
                    .orElseThrow(() -> new IllegalArgumentException("路线报名不存在"));
            var state = userRoadmapNodeRepository.findById(request.userRoadmapNodeId())
                    .orElseThrow(() -> new IllegalArgumentException("路线节点状态不存在"));
            boolean matchingScope = enrollment.getOwnerId().equals(request.ownerId())
                    && enrollment.getTemplateId().equals(request.roadmapTemplateId())
                    && state.getOwnerId().equals(request.ownerId())
                    && state.getUserRoadmapId().equals(request.userRoadmapId())
                    && state.getNodeId().equals(request.roadmapNodeId())
                    && state.getTemplateId().equals(request.roadmapTemplateId())
                    && roadmapNodeRepository.findByIdAndTemplateId(
                            request.roadmapNodeId(), request.roadmapTemplateId()).isPresent();
            if (!matchingScope) {
                throw new IllegalArgumentException("节点测验必须绑定原路线节点状态");
            }
            var allowedCoverage = new java.util.HashSet<String>();
            allowedCoverage.add(request.roadmapNodeId());
            prerequisiteRepository.findAllByTemplateIdAndNodeId(
                    request.roadmapTemplateId(), request.roadmapNodeId())
                    .forEach(edge -> allowedCoverage.add(edge.getPrerequisiteNodeId()));
            Map<String, Set<String>> highFrequencyByNode = new HashMap<>();
            for (String nodeId : allowedCoverage) {
                var node = roadmapNodeRepository.findByIdAndTemplateId(
                                nodeId, request.roadmapTemplateId())
                        .orElseThrow(() -> new IllegalArgumentException("题目覆盖节点不存在"));
                List<String> items = objectMapper.readValue(
                        node.getHighFrequencyJson(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)
                );
                highFrequencyByNode.put(nodeId, Set.copyOf(items));
            }
            long currentCoverage = request.questions().stream()
                    .filter(question -> request.roadmapNodeId().equals(question.coverageNodeId()))
                    .count();
            long practical = request.questions().stream()
                    .filter(question -> Boolean.TRUE.equals(question.practical())).count();
            boolean validQuestions = currentCoverage >= 3 && practical >= 3
                    && request.questions().stream().allMatch(question ->
                    Integer.valueOf(20).equals(question.points())
                            && allowedCoverage.contains(question.coverageNodeId())
                            && (!Boolean.TRUE.equals(question.practical())
                            || (question.highFrequencyRef() != null
                            && !question.highFrequencyRef().isBlank()
                            && highFrequencyByNode
                            .getOrDefault(question.coverageNodeId(), Set.of())
                            .contains(question.highFrequencyRef())))
                            && question.sources() != null && !question.sources().isEmpty()
                            && question.sources().stream().anyMatch(source ->
                            ("roadmap-node:" + question.coverageNodeId())
                                    .equals(source.locator())));
            if (!validQuestions) {
                throw new IllegalArgumentException("节点测验题目分值、覆盖或来源不合法");
            }
        }
        if (request.purpose() == RoadmapQuizPurpose.STAGE_GRADUATION) {
            var enrollment = userRoadmapRepository.findById(request.userRoadmapId())
                    .orElseThrow(() -> new IllegalArgumentException("路线报名不存在"));
            boolean matchingScope = enrollment.getOwnerId().equals(request.ownerId())
                    && enrollment.getTemplateId().equals(request.roadmapTemplateId())
                    && roadmapStageRepository.findByIdAndTemplateId(
                            request.roadmapStageId(), request.roadmapTemplateId()).isPresent();
            if (!matchingScope) {
                throw new IllegalArgumentException("阶段毕业测验必须属于报名路线模板");
            }
        }
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
        } else if (input.options() == null || input.options().size() < 2
                || input.correctAnswers().isEmpty()
                || !input.options().containsAll(input.correctAnswers())) {
            throw new IllegalArgumentException("选择题必须提供选项内的正确答案");
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
        userRoadmapRepository.findBoundRoadmapForQuizForUpdate(quizId, ownerId);
        QuizBundle bundle = get(ownerId, quizId);
        String idempotencyKey = request.idempotencyKey() == null
                ? UUID.randomUUID().toString()
                : request.idempotencyKey();
        var existing = attemptRepository.findByOwnerIdAndQuizIdAndIdempotencyKey(
                ownerId, quizId, idempotencyKey
        );
        if (existing.isPresent()) {
            return toAttemptResponse(existing.get(), bundle.questions());
        }
        wrongQuestionService.requireReviewOpen(ownerId, quizId);
        if (attemptRepository.existsByOwnerIdAndQuizIdAndStatus(
                ownerId, quizId, QuizAttemptStatus.EVALUATING
        )) {
            throw new ConflictException("该测验已有代码作答正在评估");
        }
        Map<String, Set<String>> submitted = new HashMap<>();
        Map<String, String> submittedCode = new HashMap<>();
        request.answers().forEach(answer -> {
            if (answer.selectedAnswers() != null) {
                submitted.put(answer.questionId(), answer.selectedAnswers());
            }
            if (answer.codeAnswer() != null && !answer.codeAnswer().isBlank()) {
                submittedCode.put(answer.questionId(), answer.codeAnswer());
            }
        });
        if (submitted.size() + submittedCode.size() != bundle.questions().size()) {
            throw new IllegalArgumentException("必须回答测验中的全部题目");
        }

        Instant now = Instant.now();
        int correctCount = 0;
        List<QuizAttemptResponse.QuestionResult> results = new ArrayList<>();
        for (QuestionEntity question : bundle.questions()) {
            if (question.getType() == QuestionType.CODING) {
                if (!submittedCode.containsKey(question.getId())) {
                    throw new IllegalArgumentException("提交的编程题与测验不匹配");
                }
                results.add(new QuizAttemptResponse.QuestionResult(
                        question.getId(), question.getType(), question.getQuestionText(),
                        question.getOptions(), Set.of(), submittedCode.get(question.getId()),
                        question.getCorrectAnswers(), question.getReferenceAnswer(),
                        false, question.getKnowledgePoint(),
                        question.getExplanation(), "PENDING_AI_EVALUATION", null, null
                ));
                continue;
            }
            Set<String> selected = submitted.get(question.getId());
            if (selected == null) {
                throw new IllegalArgumentException("提交的题目与测验不匹配");
            }
            boolean correct = question.getCorrectAnswers().equals(selected);
            if (correct) {
                correctCount++;
            }
            results.add(new QuizAttemptResponse.QuestionResult(
                    question.getId(),
                    question.getType(), question.getQuestionText(), question.getOptions(),
                    selected, null, question.getCorrectAnswers(), question.getReferenceAnswer(),
                    correct,
                    question.getKnowledgePoint(),
                    question.getExplanation(),
                    "DETERMINISTIC",
                    correct ? 100.0 : 0.0,
                    null
            ));
        }
        long choiceCount = bundle.questions().stream()
                .filter(question -> question.getType() != QuestionType.CODING)
                .count();
        double objectiveScore = choiceCount == 0 ? 0
                : correctCount * 100.0 / choiceCount;
        String attemptId = UUID.randomUUID().toString();
        QuizAttemptStatus attemptStatus = submittedCode.isEmpty()
                ? QuizAttemptStatus.GRADED
                : QuizAttemptStatus.EVALUATING;
        QuizAttemptEntity attempt = attemptRepository.save(new QuizAttemptEntity(
                attemptId,
                quizId,
                ownerId,
                objectiveScore,
                attemptStatus,
                idempotencyKey,
                objectiveScore,
                objectMapper.writeValueAsString(request),
                null,
                null,
                now
        ));
        QuizAttemptResponse.ReviewProgress reviewProgress = null;
        if (!submittedCode.isEmpty()) {
            if (bundle.quiz().getPurpose() == RoadmapQuizPurpose.NODE) {
                roadmapQuizProgressService.markEvaluating(bundle.quiz(), now);
            }
            codingJobRepository.save(new CodingEvaluationJobEntity(
                    UUID.randomUUID().toString(), attemptId, now
            ));
        } else {
            bundle.questions().stream()
                    .filter(question -> question.getType() != QuestionType.CODING)
                    .forEach(question -> recordMastery(
                            ownerId,
                            question.getKnowledgePoint(),
                            submitted.get(question.getId()).equals(question.getCorrectAnswers())
                                    ? 100.0 : 0.0,
                            1.0,
                            attemptId,
                            now
                    ));
            recordAssociatedTaskMastery(bundle.quiz(), now);
            reviewTaskCandidateService.createCandidates(
                    bundle.quiz(), attemptId, Set.of()
            );
            markLessonQuizPassed(bundle.quiz(), objectiveScore, now);
            if (bundle.quiz().getPurpose() == RoadmapQuizPurpose.NODE) {
                roadmapQuizProgressService.recordResult(bundle.quiz(), objectiveScore, now);
            }
            roadmapStageGraduationService.recordQuizResult(bundle.quiz(), objectiveScore, now);
            roadmapDiagnosticService.recordQuizResult(bundle.quiz(), bundle.questions(), results, now);
            reviewProgress = wrongQuestionService.recordTerminalAttempt(
                    bundle.quiz(), attempt, bundle.questions(), results, false, now);
        }
        return new QuizAttemptResponse(
                attemptId, quizId, objectiveScore, attemptStatus.name(), null, results,
                reviewProgress
        );
    }

    @Transactional(readOnly = true)
    public QuizAttemptResponse getAttempt(String ownerId, String attemptId) {
        QuizAttemptEntity attempt = attemptRepository.findByIdAndOwnerId(attemptId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("测验作答不存在"));
        return toAttemptResponse(
                attempt,
                questionRepository.findAllByQuizIdOrderByPosition(attempt.getQuizId())
        );
    }

    @Transactional
    public CodingJobPayload claimCodingJob(String workerId, int leaseSeconds) {
        Instant now = Instant.now();
        CodingEvaluationJobEntity job = codingJobRepository.findAll().stream()
                .filter(item -> item.claimable(now))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("暂无代码评估任务"));
        job.claim(workerId, leaseSeconds, now);
        codingJobRepository.save(job);
        QuizAttemptEntity attempt = attemptRepository.findById(job.getAttemptId())
                .orElseThrow(() -> new ResourceNotFoundException("测验作答不存在"));
        SubmitQuizAttemptRequest submitted = objectMapper.readValue(
                attempt.getAnswersJson(), SubmitQuizAttemptRequest.class
        );
        List<QuestionEntity> questions =
                questionRepository.findAllByQuizIdOrderByPosition(attempt.getQuizId());
        List<CodingAnswerPayload> answers = submitted.answers().stream()
                .filter(answer -> answer.codeAnswer() != null)
                .map(answer -> {
                    QuestionEntity question = questions.stream()
                            .filter(item -> item.getId().equals(answer.questionId()))
                            .findFirst()
                            .orElseThrow();
                    return new CodingAnswerPayload(
                            question.getId(), question.getQuestionText(), answer.codeAnswer(),
                            objectMapper.readValue(question.getRubricJson(), Map.class)
                    );
                })
                .toList();
        return new CodingJobPayload(
                job.getId(),
                attempt.getId(),
                attempt.getOwnerId(),
                answers
        );
    }

    @Transactional
    public QuizAttemptResponse completeCodingJob(
            String jobId,
            String workerId,
            List<Map<String, Object>> evaluations
    ) {
        userRoadmapRepository.findBoundRoadmapForCodingJobForUpdate(jobId);
        CodingEvaluationJobEntity job = requireOwnedJob(jobId, workerId);
        QuizAttemptEntity attempt = attemptRepository.findById(job.getAttemptId())
                .orElseThrow(() -> new ResourceNotFoundException("测验作答不存在"));
        List<QuestionEntity> questions =
                questionRepository.findAllByQuizIdOrderByPosition(attempt.getQuizId());
        QuizEntity quiz = quizRepository.findById(attempt.getQuizId()).orElseThrow();
        double finalScore = quiz.getPurpose() == RoadmapQuizPurpose.NODE
                ? calculateNodeQuizScore(attempt, questions, evaluations)
                : calculateAdaptiveQuizScore(attempt, questions, evaluations);
        Instant now = Instant.now();
        attempt.complete(finalScore, objectMapper.writeValueAsString(evaluations), now);
        job.complete(now);
        recordCompletedAttemptMastery(attempt, questions, evaluations, now);
        recordAssociatedTaskMastery(quiz, now);
        Set<String> weakCodingPoints = evaluations.stream()
                .filter(item -> ((Number) item.get("score")).doubleValue() < 70)
                .map(item -> String.valueOf(item.get("questionId")))
                .map(questionId -> questions.stream()
                        .filter(question -> question.getId().equals(questionId))
                        .findFirst().orElseThrow().getKnowledgePoint())
                .collect(java.util.stream.Collectors.toSet());
        reviewTaskCandidateService.createCandidates(quiz, attempt.getId(), weakCodingPoints);
        markLessonQuizPassed(quiz, finalScore, now);
        if (quiz.getPurpose() == RoadmapQuizPurpose.NODE) {
            roadmapQuizProgressService.recordResult(quiz, finalScore, now);
        }
        roadmapStageGraduationService.recordQuizResult(quiz, finalScore, now);
        QuizAttemptEntity savedAttempt = attemptRepository.save(attempt);
        QuizAttemptResponse response = toAttemptResponse(savedAttempt, questions);
        QuizAttemptResponse.ReviewProgress reviewProgress = wrongQuestionService.recordTerminalAttempt(
                quiz, savedAttempt, questions, response.results(), false, now);
        return new QuizAttemptResponse(
                response.id(), response.quizId(), response.score(), response.status(),
                response.warning(), response.results(), reviewProgress);
    }

    @Transactional
    public void heartbeatCodingJob(String jobId, String workerId, int leaseSeconds) {
        CodingEvaluationJobEntity job = requireOwnedJob(jobId, workerId);
        job.heartbeat(leaseSeconds, Instant.now());
        codingJobRepository.save(job);
    }

    @Transactional
    public void failCodingJob(String jobId, String workerId, String error) {
        CodingEvaluationJobEntity job = requireOwnedJob(jobId, workerId);
        job.fail(error, Instant.now());
        codingJobRepository.save(job);
        if (job.getStatus().equals("FAILED")) {
            QuizAttemptEntity attempt = attemptRepository.findById(job.getAttemptId())
                    .orElseThrow();
            attempt.partiallyGrade("代码评估失败，已仅保留客观题成绩", Instant.now());
            QuizAttemptEntity savedAttempt = attemptRepository.save(attempt);
            List<QuestionEntity> questions = questionRepository
                    .findAllByQuizIdOrderByPosition(attempt.getQuizId());
            QuizEntity quiz = quizRepository.findById(attempt.getQuizId()).orElseThrow();
            QuizAttemptResponse response = toAttemptResponse(savedAttempt, questions);
            wrongQuestionService.recordTerminalAttempt(
                    quiz, savedAttempt, questions, response.results(), false, Instant.now());
        }
    }

    private CodingEvaluationJobEntity requireOwnedJob(String jobId, String workerId) {
        CodingEvaluationJobEntity job = codingJobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("代码评估任务不存在"));
        if (!workerId.equals(job.getWorkerId()) || !job.getStatus().equals("PROCESSING")) {
            throw new ConflictException("代码评估任务租约无效");
        }
        return job;
    }

    private QuizAttemptResponse toAttemptResponse(
            QuizAttemptEntity attempt,
            List<QuestionEntity> questions
    ) {
        List<QuizAttemptResponse.QuestionResult> results = new ArrayList<>();
        SubmitQuizAttemptRequest submitted = objectMapper.readValue(
                attempt.getAnswersJson(), SubmitQuizAttemptRequest.class);
        Map<String, Set<String>> selected = submitted.answers().stream()
                .collect(java.util.stream.Collectors.toMap(
                        SubmitQuizAttemptRequest.AnswerInput::questionId,
                        answer -> answer.selectedAnswers() == null
                                ? Set.of() : answer.selectedAnswers()));
        Map<String, String> submittedCode = submitted.answers().stream()
                .filter(answer -> answer.codeAnswer() != null)
                .collect(java.util.stream.Collectors.toMap(
                        SubmitQuizAttemptRequest.AnswerInput::questionId,
                        SubmitQuizAttemptRequest.AnswerInput::codeAnswer));
        Map<String, Map<String, Object>> evaluations = attempt.getEvaluationJson() == null
                ? Map.of()
                : ((List<Map<String, Object>>) objectMapper.readValue(
                        attempt.getEvaluationJson(), List.class)).stream()
                .collect(java.util.stream.Collectors.toMap(
                        item -> String.valueOf(item.get("questionId")), item -> item));
        for (QuestionEntity question : questions) {
            if (question.getType() == QuestionType.CODING) {
                Map<String, Object> evaluation = evaluations.get(question.getId());
                if (evaluation == null) {
                    results.add(new QuizAttemptResponse.QuestionResult(
                            question.getId(), question.getType(), question.getQuestionText(),
                            question.getOptions(), Set.of(), submittedCode.get(question.getId()),
                            question.getCorrectAnswers(), question.getReferenceAnswer(),
                            false, question.getKnowledgePoint(),
                            question.getExplanation(), "PENDING_AI_EVALUATION", null, null));
                    continue;
                }
                results.add(new QuizAttemptResponse.QuestionResult(
                        question.getId(),
                        question.getType(), question.getQuestionText(), question.getOptions(),
                        Set.of(), submittedCode.get(question.getId()), question.getCorrectAnswers(),
                        question.getReferenceAnswer(),
                        ((Number) evaluation.get("score")).doubleValue() >= 70,
                        question.getKnowledgePoint(),
                        question.getExplanation(),
                        "AI_EVALUATED",
                        ((Number) evaluation.get("score")).doubleValue(),
                        evaluation
                ));
                continue;
            }
            boolean correct = selected.getOrDefault(question.getId(), Set.of())
                    .equals(question.getCorrectAnswers());
            results.add(new QuizAttemptResponse.QuestionResult(
                    question.getId(), question.getType(), question.getQuestionText(),
                    question.getOptions(), selected.getOrDefault(question.getId(), Set.of()),
                    null, question.getCorrectAnswers(), question.getReferenceAnswer(),
                    correct, question.getKnowledgePoint(),
                    question.getExplanation(), "DETERMINISTIC",
                    correct ? 100.0 : 0.0, null));
        }
        return new QuizAttemptResponse(
                attempt.getId(), attempt.getQuizId(), attempt.getScore(), attempt.getStatus().name(),
                attempt.getWarning(), results,
                wrongQuestionService.progressForQuiz(attempt.getOwnerId(), attempt.getQuizId())
        );
    }

    @Transactional
    public void backfillWrongQuestions() {
        for (QuizAttemptEntity attempt : attemptRepository.findAllByOrderByCreatedAtAsc()) {
            if (attempt.getStatus() == QuizAttemptStatus.EVALUATING) continue;
            QuizEntity quiz = quizRepository.findById(attempt.getQuizId()).orElse(null);
            if (quiz == null) continue;
            List<QuestionEntity> questions = questionRepository
                    .findAllByQuizIdOrderByPosition(attempt.getQuizId());
            QuizAttemptResponse response = toAttemptResponse(attempt, questions);
            wrongQuestionService.recordTerminalAttempt(
                    quiz, attempt, questions, response.results(), false, attempt.getCreatedAt());
        }
    }

    private double calculateAdaptiveQuizScore(
            QuizAttemptEntity attempt,
            List<QuestionEntity> questions,
            List<Map<String, Object>> evaluations
    ) {
        long choiceCount = questions.stream()
                .filter(question -> question.getType() != QuestionType.CODING).count();
        double codingTotal = evaluations.stream()
                .mapToDouble(item -> ((Number) item.get("score")).doubleValue()).sum();
        return (attempt.getObjectiveScore() * choiceCount + codingTotal * 0.3)
                / (choiceCount + evaluations.size() * 0.3);
    }

    private double calculateNodeQuizScore(
            QuizAttemptEntity attempt,
            List<QuestionEntity> questions,
            List<Map<String, Object>> evaluations
    ) {
        SubmitQuizAttemptRequest submitted = objectMapper.readValue(
                attempt.getAnswersJson(), SubmitQuizAttemptRequest.class);
        Map<String, Set<String>> selected = submitted.answers().stream()
                .collect(java.util.stream.Collectors.toMap(
                        SubmitQuizAttemptRequest.AnswerInput::questionId,
                        answer -> answer.selectedAnswers() == null
                                ? Set.of() : answer.selectedAnswers()));
        Map<String, Double> codingScores = evaluations.stream()
                .collect(java.util.stream.Collectors.toMap(
                        item -> String.valueOf(item.get("questionId")),
                        item -> ((Number) item.get("score")).doubleValue()));
        return questions.stream().mapToDouble(question -> {
            double questionScore = question.getType() == QuestionType.CODING
                    ? codingScores.getOrDefault(question.getId(), 0.0)
                    : (selected.getOrDefault(question.getId(), Set.of())
                    .equals(question.getCorrectAnswers()) ? 100.0 : 0.0);
            return question.getPoints() * questionScore / 100.0;
        }).sum();
    }

    public record CodingJobPayload(
            String jobId,
            String attemptId,
            String ownerId,
            List<CodingAnswerPayload> answers
    ) {
    }

    public record CodingAnswerPayload(
            String questionId,
            String questionText,
            String codeAnswer,
            Object rubric
    ) {
    }

    @Transactional(readOnly = true)
    public List<MasteryEntity> listMastery(String ownerId) {
        return masteryRepository.findAllByOwnerIdOrderByScoreAsc(ownerId);
    }

    @Transactional
    public List<MasteryEntity> recordSelfAssessments(
            String ownerId,
            String attemptId,
            SelfAssessmentRequest request
    ) {
        QuizAttemptEntity attempt = attemptRepository.findByIdAndOwnerId(attemptId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("测验作答不存在"));
        Set<String> allowedPoints = questionRepository
                .findAllByQuizIdOrderByPosition(attempt.getQuizId()).stream()
                .map(QuestionEntity::getKnowledgePoint)
                .collect(java.util.stream.Collectors.toSet());
        Instant now = Instant.now();
        List<MasteryEntity> updated = new ArrayList<>();
        for (SelfAssessmentRequest.Rating rating : request.ratings()) {
            if (!allowedPoints.contains(rating.knowledgePoint())) {
                throw new IllegalArgumentException("只能评价本次测验包含的知识点");
            }
            MasteryEntity mastery = masteryRepository
                    .findByOwnerIdAndKnowledgePoint(ownerId, rating.knowledgePoint())
                    .orElseThrow(() -> new ResourceNotFoundException("知识点掌握度不存在"));
            mastery.recordSelfAssessment(rating.score(), now);
            masteryEvidenceRepository.save(new MasteryEvidenceEntity(
                    UUID.randomUUID().toString(), ownerId, rating.knowledgePoint(),
                    MasteryEvidenceType.SELF_ASSESSMENT, rating.score(), 1.0,
                    attemptId, now
            ));
            updated.add(masteryRepository.save(mastery));
        }
        return updated;
    }

    private void recordCompletedAttemptMastery(
            QuizAttemptEntity attempt,
            List<QuestionEntity> questions,
            List<Map<String, Object>> evaluations,
            Instant now
    ) {
        SubmitQuizAttemptRequest submitted = objectMapper.readValue(
                attempt.getAnswersJson(), SubmitQuizAttemptRequest.class
        );
        Map<String, SubmitQuizAttemptRequest.AnswerInput> answers = submitted.answers().stream()
                .collect(java.util.stream.Collectors.toMap(
                        SubmitQuizAttemptRequest.AnswerInput::questionId,
                        answer -> answer
                ));
        Map<String, Double> codingScores = evaluations.stream()
                .collect(java.util.stream.Collectors.toMap(
                        item -> String.valueOf(item.get("questionId")),
                        item -> ((Number) item.get("score")).doubleValue()
                ));
        for (QuestionEntity question : questions) {
            double questionScore;
            double weight;
            if (question.getType() == QuestionType.CODING) {
                questionScore = codingScores.get(question.getId());
                weight = 0.3;
            } else {
                questionScore = question.getCorrectAnswers().equals(
                        answers.get(question.getId()).selectedAnswers()
                ) ? 100.0 : 0.0;
                weight = 1.0;
            }
            recordMastery(
                    attempt.getOwnerId(), question.getKnowledgePoint(), questionScore,
                    weight, attempt.getId(), now
            );
        }
    }

    /**
     * 任务信号只来自当前测验明确关联的任务，避免把无关任务完成情况混入知识点。
     */
    private void recordAssociatedTaskMastery(QuizEntity quiz, Instant now) {
        if (quiz.getTaskId() == null) {
            return;
        }
        learningTaskRepository.findByIdAndOwnerId(quiz.getTaskId(), quiz.getOwnerId())
                .filter(task -> task.getStatus() == LearningTaskStatus.COMPLETED
                        || task.getStatus() == LearningTaskStatus.SKIPPED)
                .ifPresent(task -> {
                    double taskScore = task.getStatus() == LearningTaskStatus.COMPLETED
                            ? 100.0 : 0.0;
                    questionRepository.findAllByQuizIdOrderByPosition(quiz.getId()).stream()
                            .map(QuestionEntity::getKnowledgePoint)
                            .distinct()
                            .forEach(point -> recordTaskMastery(
                                    quiz.getOwnerId(), point, taskScore, task.getId(), now
                            ));
                });
    }

    private void markLessonQuizPassed(QuizEntity quiz, double score, Instant now) {
        if (quiz.getLessonId() == null || score < 60) {
            return;
        }
        LessonProgressEntity progress = lessonProgressRepository
                .findByOwnerIdAndLessonId(quiz.getOwnerId(), quiz.getLessonId())
                .orElseGet(() -> new LessonProgressEntity(
                        UUID.randomUUID().toString(),
                        quiz.getOwnerId(),
                        quiz.getLessonId(),
                        now
                ));
        progress.markQuizPassed(now);
        lessonProgressRepository.save(progress);
    }

    private void recordTaskMastery(
            String ownerId,
            String knowledgePoint,
            double score,
            String taskId,
            Instant now
    ) {
        MasteryEntity mastery = masteryRepository
                .findByOwnerIdAndKnowledgePoint(ownerId, knowledgePoint)
                .orElseGet(() -> new MasteryEntity(
                        UUID.randomUUID().toString(), ownerId, knowledgePoint, 0, now
                ));
        mastery.recordTask(score, now);
        masteryRepository.save(mastery);
        masteryEvidenceRepository.save(new MasteryEvidenceEntity(
                UUID.randomUUID().toString(), ownerId, knowledgePoint,
                MasteryEvidenceType.TASK, score, 1.0, taskId, now
        ));
    }

    private void recordMastery(
            String ownerId,
            String knowledgePoint,
            double score,
            double evidenceWeight,
            String sourceReference,
            Instant now
    ) {
        var existing = masteryRepository.findByOwnerIdAndKnowledgePoint(
                ownerId, knowledgePoint
        );
        MasteryEntity mastery = existing
                .orElseGet(() -> new MasteryEntity(
                        UUID.randomUUID().toString(),
                        ownerId,
                        knowledgePoint,
                        score,
                        now
                ));
        if (existing.isPresent()) {
            mastery.recordQuiz(score, evidenceWeight, now);
        }
        masteryRepository.save(mastery);
        masteryEvidenceRepository.save(new MasteryEvidenceEntity(
                UUID.randomUUID().toString(), ownerId, knowledgePoint,
                MasteryEvidenceType.QUIZ, score, evidenceWeight, sourceReference, now
        ));
    }

    public record QuizBundle(QuizEntity quiz, List<QuestionEntity> questions) {
    }
}
