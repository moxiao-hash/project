# StudyPilot Granular Roadmap Learning Loop V2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade StudyPilot from a read-only 64-node roadmap into a beginner-friendly 12-stage, 24-module, 125-node learning loop with check-ins, generated quizzes, rolling schedules, milestone evidence, and a constrained local runner.

**Architecture:** Java and MySQL remain the source of truth. Python claims durable jobs for quiz generation and AI rubric review, while Vue calls only authenticated Java `/api/**` endpoints. The immutable V2 catalog is introduced beside V1, upgrades preserve only equivalent completion, and runner execution is isolated behind Java governance and a local signed socket protocol.

**Tech Stack:** Java 26, Spring Boot 4, Spring Data JPA, Flyway, MySQL/H2, Python 3.12, FastAPI, DeepSeek, Tavily, Vue 3, TypeScript, Vite, Vitest.

---

## Batch 1 — Roadmap V2 and three-level navigation

### Task 1: Add the module persistence model

**Files:**
- Create: `backend/src/main/resources/db/migration/V25__add_roadmap_modules.sql`
- Create: `backend/src/main/java/com/moxiao/studypilot/roadmap/infrastructure/RoadmapModuleEntity.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/roadmap/infrastructure/RoadmapModuleJpaRepository.java`
- Modify: `backend/src/main/java/com/moxiao/studypilot/roadmap/infrastructure/RoadmapNodeEntity.java`
- Test: `backend/src/test/java/com/moxiao/studypilot/roadmap/application/RoadmapModulePersistenceTest.java`

- [x] Write a failing migration/JPA test proving modules are template/stage scoped, ordered, uniquely coded, and every V2 node belongs to exactly one module.
- [x] Run `./mvnw -q -Dtest=RoadmapModulePersistenceTest test` and confirm the missing table/entity failure.
- [x] Add `roadmap_modules`; add nullable `module_id` for V1 compatibility and composite V2 foreign-key support; map the entity and repository.
- [x] Re-run the focused test and commit `feat: model roadmap modules`.

### Task 2: Publish the immutable V2 catalog

**Files:**
- Create: `backend/src/main/resources/roadmaps/studypilot-java-ai-v2.json`
- Modify: `backend/src/main/java/com/moxiao/studypilot/roadmap/application/RoadmapCatalogImporter.java`
- Modify: `backend/src/main/java/com/moxiao/studypilot/roadmap/application/RoadmapCatalogValidator.java`
- Test: `backend/src/test/java/com/moxiao/studypilot/roadmap/application/RoadmapV2CatalogContentTest.java`
- Test: `backend/src/test/java/com/moxiao/studypilot/roadmap/application/RoadmapCatalogImporterTest.java`

- [x] Write failing tests for exactly 12 stages, 24 modules, 125 nodes, 30–60 estimated minutes, stable unique codes, acyclic prerequisites, five-item quiz blueprints, and one milestone at each module end.
- [x] Pin the 25 Stage-1 node titles/order from the approved specification and assert that record/sealed/Stream/Optional/Checkstyle are absent from the first basic-syntax module.
- [x] Author all node-specific objectives, high-frequency points, mistakes, search keywords, artifact requirements, and practical quiz blueprints; reject repeated generic templates.
- [x] Generalize importer configuration to import V1 and V2 in order without mutating an existing checksum; persist modules before nodes and preserve optional legacy mappings.
- [x] Run focused importer/catalog tests and commit `feat: publish granular java ai roadmap v2`.

### Task 3: Harden V1-to-V2 upgrade semantics

**Files:**
- Modify: `backend/src/main/java/com/moxiao/studypilot/roadmap/application/RoadmapUpgradeService.java`
- Modify: `backend/src/main/java/com/moxiao/studypilot/roadmap/infrastructure/UserRoadmapNodeEntity.java`
- Test: `backend/src/test/java/com/moxiao/studypilot/roadmap/api/RoadmapUpgradeWorkflowTest.java`

- [x] Write failing tests showing broad V1 nodes do not fan out completion to granular V2 nodes, exact unchanged nodes may carry completion, and V1 evidence/history remains readable.
- [x] Include module-level added/removed/changed counts in the preview while retaining owner isolation, idempotency, latest-published locking, and atomic rollback.
- [x] Recalculate V2 availability after confirmed upgrade; commit `feat: preview granular roadmap upgrades`.

### Task 4: Expose module-aware roadmap queries

**Files:**
- Create: `backend/src/main/java/com/moxiao/studypilot/roadmap/api/RoadmapModuleResponse.java`
- Modify: `backend/src/main/java/com/moxiao/studypilot/roadmap/api/RoadmapController.java`
- Modify: `backend/src/main/java/com/moxiao/studypilot/roadmap/application/RoadmapQueryService.java`
- Modify: roadmap response DTOs and scoped repositories.
- Test: roadmap workflow and query service tests.

- [x] Write failing authenticated API tests for `GET /api/roadmaps/current/modules/{moduleId}` and module summaries embedded in map/stage responses.
- [x] Return module progress, milestone node, ordered nodes, prerequisites, and display status without accepting owner IDs.
- [x] Use bounded template/enrollment-scoped batch queries and make foreign/nonexistent IDs indistinguishable as 404.
- [x] Run focused/full Java tests and commit `feat: query roadmap modules`.

### Task 5: Render Stage → Module → Node navigation

**Files:**
- Create: `web/src/modules/roadmap/ModuleView.vue`
- Modify: roadmap client/types, RoadmapView, StageView, NodeView, router and navigation tests.

- [x] Write failing tests for `/roadmap/modules/:id`, named-route parameter consistency, module progress, milestone labels, locked-node explanations, and real-router reachability.
- [x] Render modules between stages and nodes, preserve accessible graph/list behavior, and keep legacy course routes as history-only compatibility pages.
- [x] Verify focused/full frontend tests, typecheck and build; commit `feat: navigate granular roadmap modules`.

## Batch 2 — Learning loop

### Task 6: Persist check-ins and durable roadmap quiz jobs

**Files:**
- Create: Flyway migration for roadmap check-ins, quiz purpose/node linkage, generation jobs and question signatures.
- Add roadmap check-in API/service/entity and generation-job claim/heartbeat/complete/fail internal APIs.
- Extend assessment quiz entities/DTOs without breaking task/lesson quizzes.

- [x] Write failing tests for authenticated owner isolation, 10–2000 character summaries, idempotent check-in, and atomic check-in plus generation-job creation.
- [x] Enforce exactly one quiz origin and purposes `NODE`, `DIAGNOSTIC`, `STAGE_GRADUATION`.
- [x] Keep check-in successful while generation remains retriable; commit `feat: create roadmap check-ins and quiz jobs`.

### Task 7: Generate grounded five-question node quizzes

**Files:**
- Extend Python assessment models/service/API and Java internal roadmap context/completion endpoints.
- Add Python and Java contract/integration tests.

- [x] Write failing tests for exactly five questions totaling 100 points, pass threshold 70, at least three current-node questions, direct-prerequisite-only spillover, and practical/high-frequency weighting.
- [x] Use Tavily only for explicitly time-sensitive blueprints, restricted to official domains; stable basics must not call Tavily.
- [x] Persist model/source/signature snapshots; retries must avoid recent signatures and retain old attempts/explanations.
- [x] Update roadmap quiz status and completion atomically under enrollment-first locking; commit `feat: generate roadmap node quizzes`.

### Task 8: Add rolling seven-day roadmap schedules

**Files:**
- Create a dedicated roadmap schedule migration, entities, repository, service, controller and tests.
- Extend user settings defaults without creating a second roadmap truth model.

- [ ] Write failing tests for `GET /api/roadmaps/current/schedule` and refresh, default Asia/Shanghai/60 minutes/weekends enabled, capacity, prerequisites, deterministic priority and idempotency.
- [ ] Roll overdue incomplete nodes forward and reschedule only future unstarted projections; never rewrite started/completed history.
- [ ] Trigger debounced refresh after completion/failure/settings changes; commit `feat: schedule rolling roadmap learning`.

### Task 9: Add diagnostic quick verification and stage graduation

**Files:**
- Add diagnostic/stage-graduation persistence, APIs and services; reuse Quiz/Attempt with explicit purpose.
- Add Java/Python tests for generation and gate transitions.

- [ ] Write failing tests for diagnostic creation/query, insufficient-question fallback, quick-verification rules, and immutable snapshots.
- [ ] A mastered ordinary node may skip check-in/practice but must pass a fresh five-question node quiz; milestones never skip.
- [ ] Require all required modules, accepted project evidence, and a ten-question 70-point graduation quiz before stage graduation.
- [ ] Commit `feat: verify roadmap placement and graduation`.

### Task 10: Build the single-page learning workflow and Today integration

**Files:**
- Modify NodeView and Today view; add check-in/quiz/schedule services, stores, components and tests.

- [ ] Write failing tests for the visible sequence objectives → self-study → summary check-in → generation → quiz → practice.
- [ ] Display `生成中`, `开始测验`, `查看解析`, `重新测验` on node and Today pages; link using real persisted quiz IDs.
- [ ] Poll Java only with cancellation/stale-response guards, accessible status announcements, retry/error/empty states and duplicate-submit prevention.
- [ ] Run frontend test/typecheck/build and commit `feat: complete roadmap daily learning loop`.

## Batch 3 — Milestone evidence and safe runner

### Task 11: Register owner-scoped workspaces and artifact submissions

- [ ] Write failing tests for workspace CRUD, canonical absolute roots, owner isolation, symlink/path traversal rejection, and artifact state transitions.
- [ ] Implement `/api/workspaces` and `/api/roadmap-artifacts`; store node/module/stage snapshot, test evidence and immutable review history.
- [ ] Commit `feat: register roadmap project evidence`.

### Task 12: Add governed dependency preparation and runner executions

- [ ] Write failing governance tests showing dependency preparation is HIGH risk, uses a dedicated confirm endpoint, stable idempotency keys, notifications and audit.
- [ ] Add runner execution preview/result models for fixed Maven Wrapper, npm and pytest argv templates only.
- [ ] Reject arbitrary shells, Git writes, keyboard/mouse/system commands and unregistered paths; commit `feat: govern roadmap runner executions`.

### Task 13: Implement the isolated local runner protocol

- [ ] Write failing protocol/security tests for Java-only Unix socket access, signed envelope, ten-minute expiry, nonce replay, canonical paths, symlink escape, timeout, concurrency, output limits, environment allowlist and offline execution.
- [ ] Implement dependency preparation as a separate confirmed operation; execute verification offline and return actionable missing-dependency errors.
- [ ] Commit `feat: run constrained local project checks`.

### Task 14: Add consented AI rubric review and final acceptance

- [ ] Write failing tests proving secrets/config/cache/sensitive files are never selected and no source reaches DeepSeek before explicit confirmation.
- [ ] Return a file manifest preview, then submit only confirmed necessary code and test output to a fixed rubric.
- [ ] Require real tests passing, AI score >=70 and user final confirmation before `ACCEPTED`; audit every transition.
- [ ] Commit `feat: review and accept roadmap artifacts`.

## Final verification and delivery

### Task 15: Complete full-stack E2E and documentation

- [ ] Add independent HTTP/E2E scripts for enrollment/upgrade, Today projection, check-in, generated quiz, retry, milestone runner, AI review and stage graduation.
- [ ] Update `项目开发步骤.md`, README, frontend integration guide and AI service documentation with startup order, polling, security boundary and known limitations.
- [ ] Run fresh Java full Maven tests, Python full pytest, Ruff, frontend full tests/typecheck/build and `git diff --check`.
- [ ] Run a real MySQL + Spring Boot + FastAPI + DeepSeek + Vue walkthrough; verify Tavily configured and degraded modes.
- [ ] Review every requirement against this plan, commit `test: complete granular roadmap learning workflow`, and push each completed batch to `origin/main` without staging user-local configuration files.
