package com.moxiao.studypilot.assessment.application;

import com.moxiao.studypilot.assessment.api.CreateWrongQuestionReviewRequest;
import com.moxiao.studypilot.assessment.api.WrongQuestionReviewResponse;
import com.moxiao.studypilot.assessment.domain.WrongQuestionReviewStatus;
import com.moxiao.studypilot.assessment.domain.WrongQuestionStatus;
import com.moxiao.studypilot.assessment.infrastructure.QuestionEntity;
import com.moxiao.studypilot.assessment.infrastructure.QuestionJpaRepository;
import com.moxiao.studypilot.assessment.infrastructure.QuizEntity;
import com.moxiao.studypilot.assessment.infrastructure.QuizJpaRepository;
import com.moxiao.studypilot.assessment.infrastructure.WrongQuestionEntryEntity;
import com.moxiao.studypilot.assessment.infrastructure.WrongQuestionEntryJpaRepository;
import com.moxiao.studypilot.assessment.infrastructure.WrongQuestionReviewEntity;
import com.moxiao.studypilot.assessment.infrastructure.WrongQuestionReviewItemEntity;
import com.moxiao.studypilot.assessment.infrastructure.WrongQuestionReviewItemJpaRepository;
import com.moxiao.studypilot.assessment.infrastructure.WrongQuestionReviewJpaRepository;
import com.moxiao.studypilot.shared.error.ConflictException;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import com.moxiao.studypilot.auth.infrastructure.UserAccountJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class WrongQuestionReviewService {
    private final WrongQuestionEntryJpaRepository entryRepository;
    private final WrongQuestionReviewJpaRepository reviewRepository;
    private final WrongQuestionReviewItemJpaRepository itemRepository;
    private final QuizJpaRepository quizRepository;
    private final QuestionJpaRepository questionRepository;
    private final UserAccountJpaRepository userRepository;

    public WrongQuestionReviewService(
            WrongQuestionEntryJpaRepository entryRepository,
            WrongQuestionReviewJpaRepository reviewRepository,
            WrongQuestionReviewItemJpaRepository itemRepository,
            QuizJpaRepository quizRepository,
            QuestionJpaRepository questionRepository,
            UserAccountJpaRepository userRepository
    ) {
        this.entryRepository = entryRepository;
        this.reviewRepository = reviewRepository;
        this.itemRepository = itemRepository;
        this.quizRepository = quizRepository;
        this.questionRepository = questionRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public WrongQuestionReviewResponse create(
            String ownerId, CreateWrongQuestionReviewRequest request
    ) {
        userRepository.findByIdForUpdate(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("用户不存在"));
        Optional<WrongQuestionReviewEntity> idempotent = reviewRepository
                .findByOwnerIdAndIdempotencyKey(ownerId, request.idempotencyKey());
        if (idempotent.isPresent()) return toResponse(idempotent.get());
        Optional<WrongQuestionReviewEntity> current = reviewRepository
                .findFirstByOwnerIdAndStatusOrderByCreatedAtDesc(
                        ownerId, WrongQuestionReviewStatus.OPEN);
        if (current.isPresent()) return toResponse(current.get());

        List<WrongQuestionEntryEntity> entries = entryRepository
                .findAllByOwnerIdAndStatus(ownerId, WrongQuestionStatus.ACTIVE).stream()
                .filter(entry -> request.chapterKey() == null
                        || request.chapterKey().equals(entry.getChapterKey()))
                .sorted(Comparator.comparingInt(WrongQuestionEntryEntity::getWrongCount).reversed()
                        .thenComparing(WrongQuestionEntryEntity::getLastWrongAt))
                .limit(5)
                .toList();
        if (entries.isEmpty()) throw new ConflictException("当前没有待重做的错题");

        Instant now = Instant.now();
        String quizId = UUID.randomUUID().toString();
        QuizEntity quiz = new QuizEntity(
                quizId, ownerId, null, null, null,
                "错题重做", "reused-original-questions", now);
        quiz.markAsWrongQuestionReview();
        quizRepository.save(quiz);

        String reviewId = UUID.randomUUID().toString();
        WrongQuestionReviewEntity review = reviewRepository.save(
                new WrongQuestionReviewEntity(
                        reviewId, ownerId, quizId, request.idempotencyKey(),
                        request.chapterKey(), entries.size(), now));
        for (int position = 0; position < entries.size(); position++) {
            WrongQuestionEntryEntity entry = entries.get(position);
            QuestionEntity source = questionRepository.findById(entry.getSourceQuestionId())
                    .orElseThrow();
            String reviewQuestionId = UUID.randomUUID().toString();
            QuestionEntity copy = new QuestionEntity(
                    reviewQuestionId, quizId, position, source.getType(), source.getDifficulty(),
                    source.getCodingKind(), source.getLanguage(), source.getKnowledgePoint(),
                    source.getQuestionText(), source.getOptions(), source.getCorrectAnswers(),
                    source.getExplanation(), source.getStarterCode(), source.getRubricJson(),
                    source.getReferenceAnswer(), source.getSources(), source.getQuestionSignature(),
                    source.getPoints(), source.getCoverageNodeId(), source.getPractical());
            questionRepository.save(copy);
            itemRepository.save(new WrongQuestionReviewItemEntity(
                    UUID.randomUUID().toString(), reviewId, entry.getId(), reviewQuestionId));
        }
        return toResponse(review);
    }

    @Transactional(readOnly = true)
    public Optional<WrongQuestionReviewResponse> current(String ownerId) {
        return reviewRepository.findFirstByOwnerIdAndStatusOrderByCreatedAtDesc(
                ownerId, WrongQuestionReviewStatus.OPEN).map(this::toResponse);
    }

    private WrongQuestionReviewResponse toResponse(WrongQuestionReviewEntity review) {
        return new WrongQuestionReviewResponse(
                review.getId(), review.getQuizId(), review.getStatus().name(),
                review.getQuestionCount(), entryRepository.countByOwnerIdAndStatus(
                review.getOwnerId(), WrongQuestionStatus.ACTIVE));
    }
}
