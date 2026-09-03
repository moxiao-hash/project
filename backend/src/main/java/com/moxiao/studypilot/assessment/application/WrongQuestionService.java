package com.moxiao.studypilot.assessment.application;

import com.moxiao.studypilot.assessment.api.QuizAttemptResponse;
import com.moxiao.studypilot.assessment.api.QuizResponse;
import com.moxiao.studypilot.assessment.api.WrongQuestionPageResponse;
import com.moxiao.studypilot.assessment.api.WrongQuestionResponse;
import com.moxiao.studypilot.assessment.api.WrongQuestionSummaryResponse;
import com.moxiao.studypilot.assessment.api.WrongQuestionReviewResponse;
import com.moxiao.studypilot.assessment.domain.WrongQuestionReviewStatus;
import com.moxiao.studypilot.assessment.domain.WrongQuestionStatus;
import com.moxiao.studypilot.assessment.infrastructure.QuestionEntity;
import com.moxiao.studypilot.assessment.infrastructure.QuestionJpaRepository;
import com.moxiao.studypilot.assessment.infrastructure.QuizAttemptEntity;
import com.moxiao.studypilot.assessment.infrastructure.QuizEntity;
import com.moxiao.studypilot.assessment.infrastructure.WrongQuestionEntryEntity;
import com.moxiao.studypilot.assessment.infrastructure.WrongQuestionEntryJpaRepository;
import com.moxiao.studypilot.assessment.infrastructure.WrongQuestionEventEntity;
import com.moxiao.studypilot.assessment.infrastructure.WrongQuestionEventJpaRepository;
import com.moxiao.studypilot.assessment.infrastructure.WrongQuestionReviewEntity;
import com.moxiao.studypilot.assessment.infrastructure.WrongQuestionReviewItemEntity;
import com.moxiao.studypilot.assessment.infrastructure.WrongQuestionReviewItemJpaRepository;
import com.moxiao.studypilot.assessment.infrastructure.WrongQuestionReviewJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class WrongQuestionService {
    private final WrongQuestionEntryJpaRepository entryRepository;
    private final WrongQuestionEventJpaRepository eventRepository;
    private final QuestionJpaRepository questionRepository;
    private final ObjectMapper objectMapper;
    private final WrongQuestionReviewJpaRepository reviewRepository;
    private final WrongQuestionReviewItemJpaRepository reviewItemRepository;
    private final WrongQuestionChapterResolver chapterResolver;

    public WrongQuestionService(
            WrongQuestionEntryJpaRepository entryRepository,
            WrongQuestionEventJpaRepository eventRepository,
            QuestionJpaRepository questionRepository,
            ObjectMapper objectMapper,
            WrongQuestionReviewJpaRepository reviewRepository,
            WrongQuestionReviewItemJpaRepository reviewItemRepository,
            WrongQuestionChapterResolver chapterResolver
    ) {
        this.entryRepository = entryRepository;
        this.eventRepository = eventRepository;
        this.questionRepository = questionRepository;
        this.objectMapper = objectMapper;
        this.reviewRepository = reviewRepository;
        this.reviewItemRepository = reviewItemRepository;
        this.chapterResolver = chapterResolver;
    }

    @Transactional
    public QuizAttemptResponse.ReviewProgress recordTerminalAttempt(
            QuizEntity quiz,
            QuizAttemptEntity attempt,
            List<QuestionEntity> questions,
            List<QuizAttemptResponse.QuestionResult> results,
            boolean redo,
            Instant now
    ) {
        WrongQuestionReviewEntity review = reviewRepository.findByQuizId(quiz.getId()).orElse(null);
        boolean reviewAttempt = redo || review != null;
        int clearedCount = 0;
        for (int index = 0; index < questions.size(); index++) {
            QuestionEntity question = questions.get(index);
            QuizAttemptResponse.QuestionResult result = results.get(index);
            if ("PENDING_AI_EVALUATION".equals(result.evaluationMethod())
                    || eventRepository.existsByAttemptIdAndQuestionId(
                    attempt.getId(), question.getId())) {
                continue;
            }
            WrongQuestionReviewItemEntity reviewItem = reviewItemRepository
                    .findByReviewQuestionId(question.getId()).orElse(null);
            WrongQuestionEntryEntity entry = reviewItem == null
                    ? entryRepository.findByOwnerIdAndSourceQuestionId(
                    attempt.getOwnerId(), question.getId()).orElse(null)
                    : entryRepository.findById(reviewItem.getEntryId()).orElseThrow();
            String answerJson = objectMapper.writeValueAsString(new StoredAnswer(
                    result.selectedAnswers(), result.codeAnswer()));
            if (entry == null && result.correct()) {
                continue;
            }
            if (entry == null) {
                WrongQuestionChapterResolver.Chapter chapter = chapterResolver.resolve(quiz);
                entry = new WrongQuestionEntryEntity(
                        UUID.randomUUID().toString(), attempt.getOwnerId(), question.getId(),
                        quiz.getId(), chapter.key(), chapter.title(), answerJson, now);
            } else if (result.correct()) {
                entry.recordCorrect(reviewAttempt, now);
                clearedCount++;
            } else {
                entry.recordWrong(answerJson, reviewAttempt, now);
            }
            entryRepository.save(entry);
            eventRepository.save(new WrongQuestionEventEntity(
                    UUID.randomUUID().toString(), entry.getId(), attempt.getOwnerId(),
                    attempt.getId(), question.getId(), result.correct(), answerJson,
                    result.score(), now));
        }
        if (review != null && review.getStatus() == WrongQuestionReviewStatus.OPEN) {
            review.complete(clearedCount, now);
            reviewRepository.save(review);
            return new QuizAttemptResponse.ReviewProgress(
                    clearedCount,
                    entryRepository.countByOwnerIdAndStatus(
                            attempt.getOwnerId(), WrongQuestionStatus.ACTIVE));
        }
        return null;
    }

    @Transactional(readOnly = true)
    public QuizAttemptResponse.ReviewProgress progressForQuiz(String ownerId, String quizId) {
        WrongQuestionReviewEntity review = reviewRepository.findByQuizId(quizId).orElse(null);
        if (review == null || !ownerId.equals(review.getOwnerId())
                || review.getStatus() != WrongQuestionReviewStatus.COMPLETED) {
            return null;
        }
        return new QuizAttemptResponse.ReviewProgress(
                review.getClearedCount(),
                entryRepository.countByOwnerIdAndStatus(ownerId, WrongQuestionStatus.ACTIVE));
    }

    @Transactional(readOnly = true)
    public void requireReviewOpen(String ownerId, String quizId) {
        WrongQuestionReviewEntity review = reviewRepository.findByQuizId(quizId).orElse(null);
        if (review != null && (!ownerId.equals(review.getOwnerId())
                || review.getStatus() != WrongQuestionReviewStatus.OPEN)) {
            throw new com.moxiao.studypilot.shared.error.ConflictException("该错题重做批次已完成");
        }
    }

    @Transactional(readOnly = true)
    public WrongQuestionPageResponse list(
            String ownerId, WrongQuestionStatus status, String chapterKey, int page, int size
    ) {
        if (page < 0 || size < 1 || size > 50) {
            throw new IllegalArgumentException("分页参数不合法");
        }
        List<WrongQuestionEntryEntity> matching = entryRepository
                .findAllByOwnerIdAndStatus(ownerId, status).stream()
                .filter(entry -> chapterKey == null || chapterKey.equals(entry.getChapterKey()))
                .sorted(Comparator.comparingInt(WrongQuestionEntryEntity::getWrongCount).reversed()
                        .thenComparing(WrongQuestionEntryEntity::getLastWrongAt))
                .toList();
        int from = Math.min(page * size, matching.size());
        int to = Math.min(from + size, matching.size());
        List<WrongQuestionResponse> items = matching.subList(from, to).stream()
                .map(this::toResponse)
                .toList();
        return new WrongQuestionPageResponse(items, matching.size(), page, size);
    }

    @Transactional(readOnly = true)
    public WrongQuestionSummaryResponse summary(String ownerId) {
        List<WrongQuestionEntryEntity> entries = entryRepository.findAllByOwnerId(ownerId);
        List<WrongQuestionSummaryResponse.ChapterSummary> chapters = entries.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        WrongQuestionEntryEntity::getChapterKey)).values().stream()
                .map(group -> new WrongQuestionSummaryResponse.ChapterSummary(
                        group.get(0).getChapterKey(), group.get(0).getChapterTitle(),
                        group.stream().filter(item -> item.getStatus() == WrongQuestionStatus.ACTIVE).count(),
                        group.stream().filter(item -> item.getStatus() == WrongQuestionStatus.MASTERED).count()))
                .sorted(Comparator.comparing(WrongQuestionSummaryResponse.ChapterSummary::chapterTitle))
                .toList();
        WrongQuestionReviewResponse current = reviewRepository
                .findFirstByOwnerIdAndStatusOrderByCreatedAtDesc(
                        ownerId, WrongQuestionReviewStatus.OPEN)
                .map(review -> new WrongQuestionReviewResponse(
                        review.getId(), review.getQuizId(), review.getStatus().name(),
                        review.getQuestionCount(), entryRepository.countByOwnerIdAndStatus(
                        ownerId, WrongQuestionStatus.ACTIVE)))
                .orElse(null);
        return new WrongQuestionSummaryResponse(
                entries.stream().filter(item -> item.getStatus() == WrongQuestionStatus.ACTIVE).count(),
                entries.stream().filter(item -> item.getStatus() == WrongQuestionStatus.MASTERED).count(),
                chapters, current);
    }

    private WrongQuestionResponse toResponse(WrongQuestionEntryEntity entry) {
        QuestionEntity question = questionRepository.findById(entry.getSourceQuestionId())
                .orElseThrow();
        StoredAnswer answer = objectMapper.readValue(entry.getLatestAnswerJson(), StoredAnswer.class);
        return new WrongQuestionResponse(
                entry.getId(), entry.getStatus(), entry.getChapterKey(), entry.getChapterTitle(),
                question.getType(), question.getDifficulty(), question.getCodingKind(),
                question.getLanguage(), question.getKnowledgePoint(), question.getQuestionText(),
                question.getOptions(), answer.selectedAnswers(), answer.codeAnswer(),
                question.getCorrectAnswers(), question.getReferenceAnswer(), question.getExplanation(),
                question.getSources().stream().map(source -> new QuizResponse.SourceResponse(
                        source.getSourceType(), source.getMaterialId(), source.getWebResultId(),
                        source.getTitle(), source.getLocator(), source.getSnippet())).toList(),
                entry.getWrongCount(), entry.getRedoCount(), entry.getFirstWrongAt(),
                entry.getLastWrongAt(), entry.getMasteredAt());
    }

    public record StoredAnswer(Set<String> selectedAnswers, String codeAnswer) {
        public StoredAnswer {
            selectedAnswers = selectedAnswers == null ? Set.of() : Set.copyOf(selectedAnswers);
        }
    }
}
