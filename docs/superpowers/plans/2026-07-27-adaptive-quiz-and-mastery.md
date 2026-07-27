# Adaptive Quiz and Mastery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to
> implement this plan task-by-task. Every production change follows red-green TDD.

**Goal:** Build a grounded five-question quiz, asynchronous text-only coding
evaluation, weighted mastery, and governed weak-point review-task loop.

**Architecture:** Spring Boot remains the canonical store and deterministic
grader. FastAPI retrieves evidence, calls DeepSeek, validates structured output,
and leases coding-evaluation jobs. Review tasks reuse plan-adjustment governance.

**Tech Stack:** Java 26, Spring Boot, JPA, Flyway, MySQL, Python 3.12, FastAPI,
Pydantic, DeepSeek, FastEmbed/Qdrant, Tavily, pytest, JUnit.

---

## Task 1: Grounded adaptive quiz generation

- [ ] Add failing Java contract tests for task assessment context, extended quiz
      types, difficulty, coding metadata, and source persistence.
- [ ] Run focused Maven tests and confirm missing-contract failures.
- [ ] Add Flyway V17 assessment schema evolution and conditional Java validation.
- [ ] Add failing Python tests for three mastery bands, fixed five-question mix,
      source validation, privacy refusal, and model-knowledge fallback.
- [ ] Run focused pytest and confirm missing-service failures.
- [ ] Implement the task assessment client, structured generator, prompt,
      deterministic validator, and `POST /internal/assessment/quizzes/generate`.
- [ ] Run focused and full Java/Python suites plus Ruff.
- [ ] Commit and push `feat: generate grounded adaptive quizzes`.

## Task 2: Attempts and coding evaluation

- [ ] Add failing Java tests for idempotent attempt submission, exact choice
      grading, hidden answers, one pending attempt, lease recovery, and history.
- [ ] Run focused Maven tests and confirm expected failures.
- [ ] Add Flyway V18 attempt-answer and coding-job tables; implement
      `EVALUATING`, `GRADED`, and `PARTIALLY_GRADED`.
- [ ] Add failing Python tests for untrusted code handling, fixed rubric output,
      completion/failure callbacks, and no code execution.
- [ ] Implement the leased Python worker and DeepSeek structured evaluator.
- [ ] Verify retries, partial grading, resubmission, and full regressions.
- [ ] Commit and push `feat: evaluate quiz attempts and coding answers`.

## Task 3: Weighted mastery

- [ ] Add failing Java tests for QUIZ/TASK/SELF_ASSESSMENT evidence, EWMA alpha
      0.4, normalized 80/15/5 components, and coding weight 0.3.
- [ ] Add Flyway V19 mastery evidence/component schema and legacy compatibility.
- [ ] Implement transactional recomputation when an attempt becomes final.
- [ ] Add self-assessment endpoint tests for ownership, range, upsert, and history.
- [ ] Extend mastery response with component scores, evidence count, and time.
- [ ] Run focused and full Maven/Python checks.
- [ ] Commit and push `feat: calculate weighted knowledge mastery`.

## Task 4: Governed weak-point tasks and E2E

- [ ] Add failing governance tests for `INSERT_REVIEW_TASK`, LOW/HIGH risk,
      maximum two operations, duplicate suppression, and atomic plan versioning.
- [ ] Add V20 task kind/provenance fields and extend plan adjustment execution.
- [ ] Generate deterministic REVIEW (30m) or CODING_PRACTICE (45m) candidates
      for the two lowest mastery points below 70.
- [ ] Reuse `SMALL_PLAN_ADJUSTMENT` grants and confirmation/audit/notification.
- [ ] Add `docs/quiz-mastery-e2e.http` and update project/AI documentation.
- [ ] Run fresh full Maven, pytest, Ruff, and `git diff --check`.
- [ ] Run real MySQL/DeepSeek/Qdrant integration; verify Tavily or degradation.
- [ ] Commit and push `test: complete adaptive quiz learning loop`.

## Fixed behavioral decisions

- Quiz generation is user-triggered and task-first; every quiz has five questions.
- Local evidence is optional. Stable fundamentals may use explicitly labelled
  `MODEL_KNOWLEDGE`; current versions/APIs require reliable web evidence.
- Private/local-only hits never reach DeepSeek or Tavily.
- Coding answers are inspected as text only and never compiled or executed.
- Coding scores are advisory (`AI_EVALUATED`) and have question weight 0.3.
- Only one pending attempt exists per owner/quiz; retries use stable idempotency.
- Mastery uses per-signal EWMA and normalized 80/15/5 component weights.
- At most two weak-point tasks are proposed per final attempt.
- Preserve user changes in `application.properties` and
  `docs/agent-api-examples.http`.
