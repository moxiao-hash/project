# StudyPilot Material RAG and Web Search Implementation Plan

> **For agentic workers:** Execute each task with test-driven development. Every production change must be preceded by a failing focused test, followed by the focused test, subsystem suite, and a small commit.

**Goal:** Build the Stage 6 material ingestion, local hybrid retrieval, traceable web search, grounded knowledge conversation, and learning-plan enrichment workflow.

**Architecture:** Spring Boot remains the canonical owner of files, processing jobs, chunks, and source records. FastAPI claims durable jobs through authenticated internal APIs, parses and indexes content locally, then composes material retrieval, Tavily results, and DeepSeek responses without writing MySQL directly.

**Tech Stack:** Java 21, Spring Boot, MySQL/Flyway, Python 3.12, FastAPI, LangGraph, pypdf, python-docx, Qdrant local mode, FastEmbed, Tavily, pytest, Ruff.

---

### Task 1: Secure material ingestion and durable processing leases

**Files:**
- Modify: `backend/src/main/java/com/moxiao/studypilot/material/**`
- Create: `backend/src/main/resources/db/migration/V14__create_material_processing.sql`
- Test: `backend/src/test/java/com/moxiao/studypilot/material/**`

- [ ] Add failing controller/service tests for TXT, Markdown, PDF, DOCX, pasted text, and URL imports.
- [ ] Add failing tests for 20 MB limits, unsupported extensions, owner isolation, safe storage names, and private URL rejection.
- [ ] Add failing lease tests for atomic claim, heartbeat, three retries, expiry recovery, completion, and failure.
- [ ] Implement local storage abstraction, import endpoints, internal content download, and processing-job APIs.
- [ ] Run focused tests and full Maven tests.
- [ ] Commit `feat: import and parse learning materials`.

### Task 2: Parsing, chunking, and structured analysis

**Files:**
- Create: `ai-service/app/material/**`
- Modify: `ai-service/app/clients/java_backend.py`
- Create: `backend/src/main/resources/db/migration/V15__create_material_chunks.sql`
- Test: `ai-service/tests/material/**` and Java material tests

- [ ] Add failing parser tests with TXT, Markdown, PDF, DOCX, table, and scanned-PDF fixtures.
- [ ] Add failing chunk tests for 450-character bounds, 60-character overlap, headings, pages, and table locators.
- [ ] Add failing privacy tests proving sensitive/local-only bodies never reach DeepSeek.
- [ ] Implement parser registry, deterministic chunker, cloud analysis for normal materials, and local-only degradation.
- [ ] Implement atomic Java analysis completion and canonical chunk persistence.
- [ ] Run focused and subsystem suites.
- [ ] Commit `feat: extract structured material knowledge`.

### Task 3: Local hybrid retrieval

**Files:**
- Create: `ai-service/app/retrieval/**`
- Modify: `ai-service/app/core/settings.py`
- Test: `ai-service/tests/retrieval/**`

- [ ] Add failing tests for dense/sparse indexing, RRF fusion, owner filtering, category priority, and index rebuilding.
- [ ] Add Qdrant local persistence and FastEmbed dependencies/configuration.
- [ ] Implement multilingual dense plus BM25 sparse points and deterministic payload identifiers.
- [ ] Implement owner-scoped hybrid queries and syllabus-aware ranking.
- [ ] Run pytest and Ruff.
- [ ] Commit `feat: retrieve materials with hybrid search`.

### Task 4: Tavily search and confirmed web ingestion

**Files:**
- Create: `backend/src/main/resources/db/migration/V16__create_web_search_sources.sql`
- Create: `ai-service/app/search/**`
- Modify: Java material APIs and Python Java client
- Test: Java web-source tests and `ai-service/tests/search/**`

- [ ] Add failing tests for Tavily mapping, missing-key degradation, error degradation, and official-domain hints.
- [ ] Add failing Java tests for source persistence, owner isolation, and idempotent confirmed import.
- [ ] Implement Tavily basic search without provider-generated answers or raw-page auto-ingestion.
- [ ] Implement Java source records and `POST /api/web-search-results/{id}/import`.
- [ ] Run focused and subsystem suites.
- [ ] Commit `feat: search web with traceable sources`.

### Task 5: Grounded multi-turn knowledge conversations

**Files:**
- Create: `ai-service/app/knowledge/**`
- Create: `ai-service/app/api/knowledge_conversations.py`
- Modify: `ai-service/app/main.py`
- Test: `ai-service/tests/knowledge/**`

- [ ] Add failing API tests for internal-token enforcement, creation, messages, retrieval modes, and missing conversations.
- [ ] Add failing service tests for per-turn retrieval, automatic web routing, citations, conflicts, and Tavily fallback.
- [ ] Add failing privacy tests proving sensitive evidence produces excerpts without cloud model/search calls.
- [ ] Implement in-memory multi-turn conversations and grounded answer prompts.
- [ ] Run full pytest and Ruff.
- [ ] Commit `feat: answer grounded knowledge questions`.

### Task 6: Learning-plan enrichment and end-to-end delivery

**Files:**
- Modify: `ai-service/app/agent/**`
- Create: `docs/material-rag-e2e.http`
- Modify: `项目开发步骤.md`
- Test: planner and end-to-end contract tests

- [ ] Add failing planner tests for user constraints, syllabus ordering, ordinary-material/web equality, citations, and source conflicts.
- [ ] Reuse the retrieval service before plan generation and expose plan citations without changing confirmation safety.
- [ ] Add reproducible HTTP requests covering import, processing, retrieval, web source import, and plan enrichment.
- [ ] Run full Maven tests, full pytest, Ruff, and `git diff --check`.
- [ ] Record real-integration steps and any environment-dependent limitations.
- [ ] Commit `test: complete material rag end-to-end workflow` and push all Stage 6 commits to `origin/main`.
