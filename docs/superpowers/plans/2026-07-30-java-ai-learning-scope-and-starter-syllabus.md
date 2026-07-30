# Java + AI Learning Scope and Starter Syllabus Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 StudyPilot 收窄为个人 Java + AI 项目学习平台，并为当前账号建立黑马程序员优先的基础资料主线。

**Architecture:** Python 使用一个集中式学习领域策略模块，统一约束计划提示词、知识问答提示词和教程类联网查询；版本事实仍以官方文档优先。首批课程不抓取或复制视频正文，而是整理为可追溯的 `SYLLABUS` 文本资料，经现有 Java 异步资料管线进入 MySQL 和 Qdrant。

**Tech Stack:** Python 3.12、FastAPI、LangChain、Tavily、Java 21、Spring Boot、MySQL、Qdrant、pytest、Maven

---

### Task 1: 集中定义 Java + AI 学习领域策略

**Files:**
- Create: `ai-service/app/study_scope.py`
- Create: `ai-service/tests/test_study_scope.py`
- Modify: `ai-service/app/prompts/learning_plan.py`
- Modify: `ai-service/app/knowledge/answering.py`
- Test: `ai-service/tests/prompts/test_learning_plan.py`
- Test: `ai-service/tests/knowledge/test_answering.py`

- [x] **Step 1: 写失败测试**

```python
def test_tutorial_query_prioritizes_itheima() -> None:
    assert build_learning_web_query("推荐 Spring Boot 学习视频").startswith(
        "黑马程序员 itheima"
    )

def test_version_query_is_not_rewritten_as_a_tutorial_query() -> None:
    assert build_learning_web_query("Spring Boot 当前支持哪个 Java 版本？") == (
        "Spring Boot 当前支持哪个 Java 版本？"
    )
```

并断言计划与知识问答系统提示包含：

```text
Java、Spring Boot、MySQL、Vue/TypeScript、Python/FastAPI、
DeepSeek API、LangChain/LangGraph、RAG/Qdrant/Tavily、Git/Docker
```

- [x] **Step 2: 运行测试并确认因缺少策略模块而失败**

Run:

```bash
cd ai-service
.venv/bin/pytest -q tests/test_study_scope.py \
  tests/prompts/test_learning_plan.py tests/knowledge/test_answering.py
```

Expected: FAIL，提示 `app.study_scope` 不存在或系统提示未包含领域范围。

- [x] **Step 3: 实现最小领域策略**

```python
STUDYPILOT_SCOPE = (
    "Java、Spring Boot、MySQL、Vue/TypeScript、Python/FastAPI、"
    "DeepSeek API、LangChain/LangGraph、RAG/Qdrant/Tavily、Git/Docker"
)

def build_learning_web_query(query: str) -> str:
    if any(marker in query.lower() for marker in TUTORIAL_MARKERS):
        return f"黑马程序员 itheima B站 {query}"
    return query
```

计划和知识问答提示统一引用 `STUDYPILOT_SCOPE_POLICY`，明确教程优先黑马、时效事实优先官方文档、无关主题不扩展为泛学习计划。

- [x] **Step 4: 运行针对性测试**

Run:

```bash
cd ai-service
.venv/bin/pytest -q tests/test_study_scope.py \
  tests/prompts/test_learning_plan.py tests/knowledge/test_answering.py
```

Expected: PASS。

### Task 2: 让教程类问题主动联网并优先搜索黑马程序员

**Files:**
- Modify: `ai-service/app/knowledge/service.py`
- Modify: `ai-service/app/agent/grounding.py`
- Test: `ai-service/tests/knowledge/test_service.py`
- Test: `ai-service/tests/agent/test_grounding.py`

- [x] **Step 1: 写失败测试**

```python
async def test_tutorial_question_searches_web_with_itheima_priority() -> None:
    await service.send_message(
        conversation_id,
        "推荐 Spring Boot 学习视频",
        WebSearchPolicy.AUTO,
        "user-1",
    )
    assert web.calls == [
        ("user-1", "黑马程序员 itheima B站 推荐 Spring Boot 学习视频")
    ]
```

另加计划检索测试：明确出现“课程、教程、视频、学习资料、学习路线”时使用同一查询改写；普通计划与版本查询保持原逻辑。

- [x] **Step 2: 运行测试并确认 AUTO 当前不会搜索教程问题**

Run:

```bash
cd ai-service
.venv/bin/pytest -q \
  tests/knowledge/test_service.py::test_tutorial_question_searches_web_with_itheima_priority \
  tests/agent/test_grounding.py
```

Expected: FAIL，`web.calls` 为空或查询仍为原文。

- [x] **Step 3: 实现教程意图识别与查询改写**

知识问答的 AUTO 联网标记增加教程意图；真正提交 Tavily 前调用：

```python
web_query = build_learning_web_query(message)
outcome = await web_searcher.search(conversation.owner_id, web_query)
```

计划检索只在用户明确询问教程、课程、视频、资料或路线时启用该逻辑，避免每次生成计划都消耗 Tavily 额度。

- [x] **Step 4: 运行相关 Python 测试与 Ruff**

Run:

```bash
cd ai-service
.venv/bin/pytest -q tests/knowledge/test_service.py tests/agent/test_grounding.py
.venv/bin/ruff check app tests
```

Expected: PASS，Ruff 无错误。

### Task 3: 建立首批黑马程序员优先的 SYLLABUS

**Files:**
- Create: `docs/studypilot-java-ai-starter-syllabus.md`
- Modify: `README.md`
- Modify: `docs/studypilot-product-requirements.md`

- [x] **Step 1: 编写可直接导入的课程大纲**

大纲固定顺序：

```text
Java 基础
→ Spring Boot 3 与 REST API
→ MySQL 与 JPA
→ Vue 3 与 TypeScript
→ Python 与 FastAPI
→ DeepSeek API 与提示词
→ LangChain/LangGraph 与 Agent
→ RAG、Qdrant 与 Tavily
→ 测试、安全、Git 与 Docker
```

每个阶段写明项目对应模块、最低学习目标、黑马主资料、官方补充资料和暂不学习内容。只保存摘要与链接，不复制视频字幕或付费材料。

- [x] **Step 2: 更新产品边界文档**

README 和产品需求明确：

```text
StudyPilot 是 moxiao 个人使用的 Java + AI 智能应用开发学习平台，
不面向通用学科、教师端或多人课程市场。
```

资料规则固定为：用户约束 > 本地 SYLLABUS > 普通资料与联网结果；教程优先黑马，版本事实优先官方。

- [x] **Step 3: 校验 Markdown 和差异**

Run:

```bash
git diff --check
rg -n "Java \\+ AI|黑马程序员|SYLLABUS" \
  README.md docs/studypilot-product-requirements.md \
  docs/studypilot-java-ai-starter-syllabus.md
```

Expected: 三份文档均包含新的产品范围和资料策略。

- [x] **Step 4: 通过公共 API 导入当前账号**

登录后读取大纲文件内容，调用：

```http
POST /api/materials/text
Authorization: Bearer <current-user-token>
Content-Type: application/json

{
  "title": "StudyPilot Java + AI 基础学习路线",
  "content": "<docs/studypilot-java-ai-starter-syllabus.md 内容>",
  "category": "SYLLABUS",
  "privacyLevel": "NORMAL"
}
```

Expected: 201，资料状态先为 `PENDING`，随后经 Python 处理进入 `READY`。

- [x] **Step 5: 真实检索验证**

在知识问答中发送“推荐 Spring Boot 学习视频”，确认：

```text
本地引用包含 StudyPilot Java + AI 基础学习路线
联网查询优先包含黑马程序员关键词
回答不会扩展为与项目无关的泛学习内容
```

### Task 4: 全量验证、记录与提交

**Files:**
- Modify: `项目开发步骤.md`

- [x] **Step 1: 记录本次产品收窄和资料入库结果**

新增“阶段 8 后续优化：Java + AI 垂直学习范围”，写明代码行为、资料 ID、处理状态、来源策略和已知限制。

- [x] **Step 2: 运行全量验证**

Run:

```bash
cd backend && ./mvnw test
cd ../ai-service && .venv/bin/pytest -q && .venv/bin/ruff check .
cd .. && git diff --check
```

Expected:

```text
Java: 100 tests, 0 failures
Python: 现有测试加新增测试全部通过
Ruff: All checks passed
git diff --check: 无输出
```

- [x] **Step 3: 精确提交并推送**

不暂存用户修改的：

```text
backend/src/main/resources/application.properties
docs/agent-api-examples.http
```

提交：

```bash
git commit -m "feat: specialize learning around Java AI stack"
git push origin main
```
