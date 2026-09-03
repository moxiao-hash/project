# StudyPilot Wrong Question Book Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:test-driven-development and implement every checkbox in order.

**Goal:** Persist every graded wrong answer, present it with answers and explanations, and let the user clear active mistakes by redoing up to five original questions.

**Architecture:** Java remains the authenticated source of truth. Terminal quiz attempts produce idempotent wrong-answer events and an aggregated per-user entry. Redo creates a normal quiz containing immutable copies of selected source questions, while mapping those copies back to their canonical wrong entries. Vue adds one sidebar destination and reuses the existing quiz/attempt flow.

**Tech Stack:** Java 21, Spring Boot, JPA, Flyway/MySQL, JUnit/MockMvc, Vue 3, TypeScript, Vitest.

---

### Task 1: Persist wrong answers

**Files:**
- Create: `backend/src/main/resources/db/migration/V37__create_wrong_question_book.sql`
- Create: `backend/src/main/java/com/moxiao/studypilot/assessment/domain/WrongQuestionStatus.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/assessment/infrastructure/WrongQuestionEntryEntity.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/assessment/infrastructure/WrongQuestionEventEntity.java`
- Create: corresponding JPA repositories
- Create: `backend/src/main/java/com/moxiao/studypilot/assessment/application/WrongQuestionService.java`
- Test: `backend/src/test/java/com/moxiao/studypilot/assessment/api/WrongQuestionWorkflowTest.java`

- [ ] Write MockMvc tests proving a wrong choice is listed and a correct choice is not.
- [ ] Run `cd backend && ./mvnw -Dtest=WrongQuestionWorkflowTest test` and confirm missing API/records fail.
- [ ] Add Flyway V37 tables, entities, repositories, and terminal-attempt synchronization.
- [ ] Extend attempt results with the question, submitted answer, correct/reference answer, and explanation.
- [ ] Re-run the focused test and confirm green.
- [ ] Add tests for duplicate errors, later correct answers, owner isolation, coding scores, and partial grading.
- [ ] Add an idempotent startup backfill that replays only terminal historical attempts into unique attempt/question events.
- [ ] Commit only task files as `feat: track incorrect quiz answers`.

### Task 2: Build five-question redo batches

**Files:**
- Extend: `backend/src/main/resources/db/migration/V37__create_wrong_question_book.sql`
- Create: redo batch/item entities, repositories, request/response DTOs and controller
- Modify: quiz entity/response/service for `QuizKind`
- Test: `backend/src/test/java/com/moxiao/studypilot/assessment/api/WrongQuestionReviewWorkflowTest.java`

- [ ] Write tests for all-chapter and chapter-filtered batches, deterministic ordering, final batches below five, idempotency, current-batch recovery, and empty state.
- [ ] Run the focused test and confirm red for missing review endpoints.
- [ ] Implement `GENERATED` and `WRONG_QUESTION_REVIEW`, copying source questions without exposing answers before submission.
- [ ] Enforce one unfinished batch per user and map copied questions to canonical wrong entries.
- [ ] Complete the batch after terminal grading; clear correct entries and retain incorrect/unevaluated ones.
- [ ] Verify redo quizzes update mastery but do not advance roadmap completion or create recursive review-task candidates.
- [ ] Re-run both wrong-question test classes and commit as `feat: redo wrong questions in batches`.

### Task 3: Add the wrong-question UI

**Files:**
- Create: `web/src/modules/assessment/WrongQuestionsView.vue`
- Create: `web/src/modules/assessment/WrongQuestionsView.spec.ts`
- Modify: `web/src/components/AppShell.vue`
- Modify: `web/src/app/router.ts`
- Modify: `web/src/services/current/assessment.ts`
- Modify: `web/src/types/api.ts`
- Modify: existing quiz and attempt views/tests

- [ ] Write Vitest tests for the learning navigation item, active/mastered filters, chapter selector, answer cards, redo entry, resume behavior, result progress, and cleared empty state.
- [ ] Run `cd web && npm test -- WrongQuestionsView.spec.ts` and confirm red.
- [ ] Add typed API adapters and the `/wrong-questions` route.
- [ ] Implement review and redo tabs using existing cards, badges, spacing, colors, and responsive breakpoints.
- [ ] Adapt quiz/result pages for wrong-review context and complete answer comparison.
- [ ] Re-run focused frontend tests and commit as `feat: add wrong question book interface`.

### Task 4: Documentation and full verification

**Files:**
- Modify: `项目开发步骤.md`
- Modify: `docs/前端开发对接说明.md`

- [ ] Document endpoints, lifecycle, historical backfill, no-extra-generation rule, and coding-evaluation limitation.
- [ ] Run `cd backend && ./mvnw test`.
- [ ] Run `cd ai-service && .venv/bin/python -m pytest` and `.venv/bin/python -m ruff check app tests`.
- [ ] Run `cd web && npm test`, `npm run typecheck`, and `npm run build`.
- [ ] Run `git diff --check` and inspect `git status --short` to exclude protected local files.
- [ ] Push the three feature commits plus the documentation update to `origin/main`.
