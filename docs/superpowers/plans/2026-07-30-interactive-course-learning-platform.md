# Interactive Course Learning Platform Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 StudyPilot 从“学习监督工具”升级为可以直接完成 Java + AI 课程学习、课内提问、练习、反馈和进度推进的教学平台。

**Architecture:** Java 保存课程、课时、来源、学习进度和测验等业务事实；Python 提供限定在当前课时上下文内、仅在用户主动提问时调用的 AI 导师；Vue 以“继续学习”为第一入口，展示清晰路线、B 站官方外链播放器、站内结构化讲义、StudyPilot 真实代码、课内导师和测验。视频始终由 B 站托管，系统只保存公开链接、BV 号和分 P，不抓取、下载、转存视频或字幕；播放器不可用时跳转 B 站原页面。

**Tech Stack:** Java 17、Spring Boot、MySQL 8、Flyway、Python 3.12、FastAPI、DeepSeek、LangChain、Qdrant/Tavily、Vue 3、TypeScript、Vite、Vitest

---

## Product Decisions

### 教学闭环

```text
课程中心
→ 进入课时
→ 在站内通过 B 站官方外链播放器观看黑马原课程
→ 阅读站内讲义与 StudyPilot 项目代码
→ 在课内向 AI 导师追问
→ 完成检查题与课时测验
→ 更新掌握度和课时进度
→ 继续下一课或进入薄弱点复习
```

### 第一节示范课

```text
课程：StudyPilot Java + AI 智能应用开发
模块：Spring Boot 3 与 REST API
课时：Controller、REST API 与参数校验
黑马视频：BV14z4y1N7pg，第 15、16 分 P
项目代码：AuthenticationController、RegisterRequest、GlobalExceptionHandler
官方资料：Spring MVC Annotated Controllers / Request Mapping
```

### B 站与额度边界

- 使用 B 站官方站外播放器：
  `https://player.bilibili.com/player.html?bvid={bvid}&p={page}`。
- 每个视频同时保留
  `https://www.bilibili.com/video/{bvid}?p={page}` 原页面链接作为回退。
- 加载路线、讲义和链接不调用 Tavily 或 DeepSeek，不消耗外部 AI API 额度。
- 嵌入播放器和视频流量由用户浏览器直接访问 B 站，不经过 StudyPilot Java/Python 服务。
- Tavily 只在用户主动要求搜索新资料或核实最新事实时调用；已整理的课程链接不重复搜索。
- DeepSeek 只在用户主动向课内导师提问、生成测验或提交代码评估时调用；重复打开课时
  不触发模型。
- Qdrant、MySQL 和课程进度均为本地能力，不消耗 Tavily 搜索额度。
- 不依赖未公开 API 作为运行时能力；BV 号、分 P 标题和页码由课程资源人工校对后保存。
- 跨域 iframe 无法稳定读取播放秒数，V1 由用户点击“本段视频已学习”记录进度，
  不伪造 B 站观看同步。
- 不下载、缓存、转码、去水印或重新托管视频，不保存 B 站 Cookie 或账号凭据。

---

### Task 1: Java 课程、课时与学习进度事实中心

**Files:**
- Create: `backend/src/main/resources/db/migration/V22__create_course_learning.sql`
- Create: `backend/src/main/java/com/moxiao/studypilot/course/domain/CoursePublicationStatus.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/course/domain/LessonProgressStatus.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/course/domain/LessonSourceType.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/course/infrastructure/CourseEntity.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/course/infrastructure/CourseModuleEntity.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/course/infrastructure/LessonEntity.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/course/infrastructure/LessonSourceEntity.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/course/infrastructure/LessonProgressEntity.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/course/infrastructure/*JpaRepository.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/course/application/CourseLearningService.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/course/api/CourseController.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/course/api/CourseSummaryResponse.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/course/api/CourseDetailResponse.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/course/api/LessonResponse.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/course/api/UpdateLessonProgressRequest.java`
- Test: `backend/src/test/java/com/moxiao/studypilot/course/api/CourseLearningWorkflowTest.java`

- [x] **Step 1: 写数据库和用户隔离失败测试**

测试必须覆盖：

```java
@Test
void listsPublishedCoursesAndReturnsTheCurrentUsersProgress() {
    var response = get("/api/courses", ownerOneToken);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body().get(0).progressPercent()).isZero();
}

@Test
void lessonProgressCannotBeReadOrUpdatedByAnotherOwner() {
    put("/api/lessons/lesson-rest-controller/progress", ownerOneToken, """
        {"videoCompleted":true,"readingCompleted":true,"practiceCompleted":false}
        """);
    var response = get("/api/lessons/lesson-rest-controller", ownerTwoToken);
    assertThat(response.body().progress().videoCompleted()).isFalse();
}

@Test
void completionRequiresVideoReadingAndPractice() {
    var response = put("/api/lessons/lesson-rest-controller/progress", ownerOneToken, """
        {"videoCompleted":true,"readingCompleted":true,"practiceCompleted":false}
        """);
    assertThat(response.body().progress().status()).isEqualTo("IN_PROGRESS");
}
```

- [x] **Step 2: 运行测试并确认失败**

Run:

```bash
cd backend
./mvnw -Dtest=CourseLearningWorkflowTest test
```

Expected: FAIL，课程表、Controller 和响应类型尚不存在。

- [x] **Step 3: 创建 V22 迁移**

迁移创建：

```sql
CREATE TABLE courses (
    id VARCHAR(36) PRIMARY KEY,
    slug VARCHAR(120) NOT NULL UNIQUE,
    title VARCHAR(180) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    tech_stack VARCHAR(500) NOT NULL,
    publication_status VARCHAR(20) NOT NULL,
    version INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE course_modules (
    id VARCHAR(36) PRIMARY KEY,
    course_id VARCHAR(36) NOT NULL,
    module_order INT NOT NULL,
    title VARCHAR(180) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    CONSTRAINT fk_course_modules_course
        FOREIGN KEY (course_id) REFERENCES courses(id),
    CONSTRAINT uk_course_module_order UNIQUE (course_id, module_order)
);

CREATE TABLE lessons (
    id VARCHAR(80) PRIMARY KEY,
    module_id VARCHAR(36) NOT NULL,
    lesson_order INT NOT NULL,
    slug VARCHAR(140) NOT NULL UNIQUE,
    title VARCHAR(180) NOT NULL,
    summary VARCHAR(2000) NOT NULL,
    estimated_minutes INT NOT NULL,
    content_json LONGTEXT NOT NULL,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_lessons_module FOREIGN KEY (module_id) REFERENCES course_modules(id),
    CONSTRAINT uk_lesson_order UNIQUE (module_id, lesson_order)
);

CREATE TABLE lesson_sources (
    id VARCHAR(36) PRIMARY KEY,
    lesson_id VARCHAR(80) NOT NULL,
    source_order INT NOT NULL,
    source_type VARCHAR(20) NOT NULL,
    title VARCHAR(300) NOT NULL,
    url VARCHAR(2048) NOT NULL,
    locator VARCHAR(300) NULL,
    bvid VARCHAR(20) NULL,
    video_page INT NULL,
    CONSTRAINT fk_lesson_sources_lesson FOREIGN KEY (lesson_id) REFERENCES lessons(id)
);

CREATE TABLE lesson_progress (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    lesson_id VARCHAR(80) NOT NULL,
    status VARCHAR(20) NOT NULL,
    video_completed BOOLEAN NOT NULL DEFAULT FALSE,
    reading_completed BOOLEAN NOT NULL DEFAULT FALSE,
    practice_completed BOOLEAN NOT NULL DEFAULT FALSE,
    last_section_key VARCHAR(120) NULL,
    started_at TIMESTAMP(6) NULL,
    completed_at TIMESTAMP(6) NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_lesson_progress_owner FOREIGN KEY (owner_id) REFERENCES app_users(id),
    CONSTRAINT fk_lesson_progress_lesson FOREIGN KEY (lesson_id) REFERENCES lessons(id),
    CONSTRAINT uk_lesson_progress_owner UNIQUE (owner_id, lesson_id)
);
```

- [x] **Step 4: 实现最小公共契约**

公共 API 固定为：

```text
GET /api/courses
GET /api/courses/{courseSlug}
GET /api/lessons/{lessonId}
PUT /api/lessons/{lessonId}/progress
GET /api/courses/continue
```

进度请求：

```java
public record UpdateLessonProgressRequest(
        boolean videoCompleted,
        boolean readingCompleted,
        @Size(max = 120) String lastSectionKey
) {
}
```

`practiceCompleted` 不接受前端输入，只能由 Java 根据检查题和课时测验结果更新，避免
客户端伪造课程完成。

Service 必须根据三个完成标志确定状态：

```java
private LessonProgressStatus resolveStatus(LessonProgressEntity progress) {
    if (progress.isVideoCompleted()
            && progress.isReadingCompleted()
            && progress.isPracticeCompleted()) {
        return LessonProgressStatus.COMPLETED;
    }
    return progress.hasAnyActivity()
            ? LessonProgressStatus.IN_PROGRESS
            : LessonProgressStatus.NOT_STARTED;
}
```

`GET /api/courses/continue` 返回当前用户第一个未完成课时；没有进度时返回课程第一课，
全部完成时返回 204。

- [x] **Step 5: 运行局部测试**

Run:

```bash
cd backend
./mvnw -Dtest=CourseLearningWorkflowTest test
```

Expected: PASS。

- [x] **Step 6: 提交**

```bash
git add backend/src/main/resources/db/migration/V22__create_course_learning.sql \
  backend/src/main/java/com/moxiao/studypilot/course \
  backend/src/test/java/com/moxiao/studypilot/course
git commit -m "feat: add structured course learning model"
git push origin main
```

---

### Task 2: 导入首门课程和第一节可学习示范课

**Files:**
- Create: `backend/src/main/resources/courses/studypilot-java-ai-v1.json`
- Create: `backend/src/main/java/com/moxiao/studypilot/course/application/CourseCatalogImporter.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/course/config/CourseCatalogConfiguration.java`
- Test: `backend/src/test/java/com/moxiao/studypilot/course/application/CourseCatalogImporterTest.java`

- [x] **Step 1: 写幂等导入和来源校验失败测试**

```java
@Test
void importsTheSameCatalogTwiceWithoutDuplicatingLessons() {
    importer.importCatalog();
    importer.importCatalog();
    assertThat(courseRepository.count()).isEqualTo(1);
    assertThat(lessonRepository.count()).isEqualTo(1);
}

@Test
void rejectsNonBilibiliVideoHosts() {
    assertThatThrownBy(() -> importer.validateVideo(
            "https://example.com/video/BV14z4y1N7pg", "BV14z4y1N7pg", 15
    )).isInstanceOf(IllegalArgumentException.class);
}
```

- [x] **Step 2: 运行测试并确认失败**

Run:

```bash
cd backend
./mvnw -Dtest=CourseCatalogImporterTest test
```

Expected: FAIL，导入器和课程资源不存在。

- [x] **Step 3: 编写课程资源**

`studypilot-java-ai-v1.json` 使用稳定 ID。课程目录先导入
`docs/studypilot-java-ai-starter-syllabus.md` 中的完整九阶段路线；未制作完成的模块
返回 `COMING_SOON`，但仍展示对应黑马主课程和官方资料链接。第一课内容结构如下：

```json
{
  "slug": "studypilot-java-ai",
  "title": "StudyPilot Java + AI 智能应用开发",
  "description": "通过开发 StudyPilot 学习 Java 后端、Python Agent 和 Vue 前端。",
  "techStack": "Java,Spring Boot,MySQL,Vue,TypeScript,Python,FastAPI,DeepSeek,LangGraph,RAG",
  "version": 1,
  "modules": [
    {
      "id": "module-spring-rest",
      "order": 1,
      "title": "Spring Boot 3 与 REST API",
      "description": "理解浏览器请求如何进入 Java 业务层。",
      "lessons": [
        {
          "id": "lesson-rest-controller",
          "slug": "controller-rest-api-validation",
          "order": 1,
          "title": "Controller、REST API 与参数校验",
          "summary": "从注册接口理解 Controller、DTO、校验与异常响应。",
          "estimatedMinutes": 90,
          "blocks": [
            {
              "key": "objectives",
              "type": "OBJECTIVES",
              "title": "学完你能做到",
              "markdown": "解释 Controller 的职责；手写 POST 接口；区分 DTO 校验与业务校验。"
            },
            {
              "key": "request-flow",
              "type": "EXPLANATION",
              "title": "一次请求如何流动",
              "markdown": "浏览器请求先进入 Controller，再由 Service 执行业务规则，最终返回 DTO。"
            },
            {
              "key": "project-code",
              "type": "PROJECT_CODE",
              "title": "StudyPilot 真实代码",
              "projectPath": "backend/src/main/java/com/moxiao/studypilot/auth/api/AuthenticationController.java",
              "markdown": "观察 @RestController、@PostMapping、@Valid 和请求 DTO。"
            },
            {
              "key": "checkpoint",
              "type": "CHECKPOINT",
              "title": "检查理解",
              "question": "为什么注册接口接收 RegisterRequest，而不是直接接收 Entity？",
              "options": ["避免暴露持久化模型并集中输入校验", "减少 HTTP 请求数量", "绕过 Service"],
              "correctOption": 0,
              "explanation": "DTO 明确公共契约，避免前端直接控制持久化字段。"
            },
            {
              "key": "summary",
              "type": "SUMMARY",
              "title": "本课总结",
              "markdown": "Controller 负责 HTTP 契约，Service 负责业务规则，Entity 负责持久化。"
            }
          ],
          "sources": [
            {
              "type": "VIDEO",
              "title": "黑马程序员 SpringBoot3+Vue3：注册接口",
              "url": "https://www.bilibili.com/video/BV14z4y1N7pg?p=15",
              "locator": "实战篇-03_注册接口",
              "bvid": "BV14z4y1N7pg",
              "videoPage": 15
            },
            {
              "type": "VIDEO",
              "title": "黑马程序员 SpringBoot3+Vue3：注册接口参数校验",
              "url": "https://www.bilibili.com/video/BV14z4y1N7pg?p=16",
              "locator": "实战篇-04_注册接口参数校验",
              "bvid": "BV14z4y1N7pg",
              "videoPage": 16
            },
            {
              "type": "OFFICIAL_DOC",
              "title": "Spring MVC Annotated Controllers",
              "url": "https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller.html",
              "locator": "Annotated Controllers"
            },
            {
              "type": "PROJECT_CODE",
              "title": "StudyPilot AuthenticationController",
              "url": "project://backend/src/main/java/com/moxiao/studypilot/auth/api/AuthenticationController.java",
              "locator": "注册与登录端点"
            }
          ]
        }
      ]
    }
  ]
}
```

公共响应不得返回 `correctOption`；答案只保存在 Java 端用于确定性判分。

- [x] **Step 4: 实现启动时幂等导入**

导入器以 `slug + version` 更新课程，以稳定 ID upsert 模块、课时和来源。校验规则：

```java
private static final Pattern BVID = Pattern.compile("BV[0-9A-Za-z]{10}");
private static final Set<String> VIDEO_HOSTS = Set.of(
        "www.bilibili.com",
        "bilibili.com"
);
```

只有 `VIDEO` 来源允许 `bvid/videoPage`；页码必须大于 0；课程导入失败时阻止启动并输出
不包含秘密的结构化错误。

- [x] **Step 5: 运行局部测试**

Run:

```bash
cd backend
./mvnw -Dtest=CourseCatalogImporterTest,CourseLearningWorkflowTest test
```

Expected: PASS，API 中看不到检查题答案。

- [x] **Step 6: 提交**

```bash
git add backend/src/main/resources/courses \
  backend/src/main/java/com/moxiao/studypilot/course \
  backend/src/test/java/com/moxiao/studypilot/course
git commit -m "feat: publish first project based lesson"
git push origin main
```

---

### Task 3: 课内 AI 导师与上下文教学

**Files:**
- Create: `backend/src/main/java/com/moxiao/studypilot/course/api/InternalLessonContextController.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/course/api/InternalLessonContextResponse.java`
- Modify: `backend/src/main/java/com/moxiao/studypilot/agent/api/AgentFacadeController.java`
- Create: `ai-service/app/teaching/__init__.py`
- Create: `ai-service/app/teaching/models.py`
- Create: `ai-service/app/teaching/answering.py`
- Create: `ai-service/app/teaching/service.py`
- Create: `ai-service/app/api/teaching_conversations.py`
- Modify: `ai-service/app/main.py`
- Test: `backend/src/test/java/com/moxiao/studypilot/course/api/InternalLessonContextContractTest.java`
- Test: `backend/src/test/java/com/moxiao/studypilot/agent/api/TeachingFacadeControllerTest.java`
- Test: `ai-service/tests/teaching/test_answering.py`
- Test: `ai-service/tests/teaching/test_service.py`
- Test: `ai-service/tests/api/test_teaching_conversations.py`

- [ ] **Step 1: 写课时隔离和教学策略失败测试**

```python
async def test_tutor_receives_the_current_lesson_and_visible_history():
    snapshot = await service.send_message(
        conversation_id,
        owner_id="user-1",
        message="我还是不理解 DTO 为什么不能换成 Entity",
    )
    assert answerer.calls[0]["lesson"]["id"] == "lesson-rest-controller"
    assert "DTO" in answerer.calls[0]["question"]
    assert snapshot.suggested_actions == ["CHECK_UNDERSTANDING"]


async def test_tutor_does_not_claim_to_have_watched_the_video():
    await answerer.answer(question="视频里老师说了什么", lesson=lesson, history=[])
    system = str(model.messages[0].content)
    assert "不得声称已经观看或转录 B 站视频" in system
```

Java 测试断言浏览器请求不接受 `ownerId`，由 Bearer Token 注入；Python 内部路由必须
验证 `X-Owner-Id` 与会话 owner 一致。

- [ ] **Step 2: 运行测试并确认失败**

Run:

```bash
cd backend
./mvnw -Dtest=InternalLessonContextContractTest,TeachingFacadeControllerTest test

cd ../ai-service
.venv/bin/pytest -q tests/teaching tests/api/test_teaching_conversations.py
```

Expected: FAIL，教学会话尚不存在。

- [ ] **Step 3: 实现 Java 课时上下文和公共门面**

内部上下文：

```text
GET /internal/teaching/lessons/{lessonId}/context?ownerId={ownerId}
```

公共门面：

```text
POST /api/agent/teaching-conversations
POST /api/agent/teaching-conversations/{id}/messages
GET  /api/agent/teaching-conversations/{id}
```

创建请求只接受：

```json
{"lessonId":"lesson-rest-controller"}
```

Java 转发时注入当前登录用户，拒绝前端传入的 owner 字段。

- [ ] **Step 4: 实现 Python 教学会话**

响应模型：

```python
class TeachingConversationSnapshot(BaseModel):
    conversation_id: str = Field(alias="conversationId")
    lesson_id: str = Field(alias="lessonId")
    answer: str = ""
    citations: list[KnowledgeCitation] = []
    suggested_actions: list[
        Literal["CHECK_UNDERSTANDING", "SHOW_EXAMPLE", "GIVE_HINT", "CONTINUE_LESSON"]
    ] = []
    model_provider: str = Field(alias="modelProvider")
    model_name: str = Field(alias="modelName")
```

系统提示固定包含：

```text
你是 StudyPilot 当前课时的 AI 导师，不是通用聊天机器人。
先判断学生卡在哪里，再用短解释、类比或一个项目例子教学。
优先给提示和检查理解，不要在练习未尝试前直接给完整答案。
只引用提供的课时讲义、项目代码元数据、用户资料和网页证据。
不得声称已经观看或转录 B 站视频；视频标题和分 P 只作为课程定位信息。
若问题超出 Java + AI 项目范围，简短说明边界并引导回当前课程。
```

会话通过现有 `AgentPersistence` 以 `kind="teaching"` 保存，FastAPI 重启后可恢复。

- [ ] **Step 5: 运行局部测试和 Ruff**

Run:

```bash
cd backend
./mvnw -Dtest=InternalLessonContextContractTest,TeachingFacadeControllerTest test

cd ../ai-service
.venv/bin/pytest -q tests/teaching tests/api/test_teaching_conversations.py
.venv/bin/ruff check app tests
```

Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/moxiao/studypilot/course \
  backend/src/main/java/com/moxiao/studypilot/agent/api/AgentFacadeController.java \
  backend/src/test/java/com/moxiao/studypilot/course \
  backend/src/test/java/com/moxiao/studypilot/agent/api/TeachingFacadeControllerTest.java \
  ai-service/app/teaching ai-service/app/api/teaching_conversations.py \
  ai-service/app/main.py ai-service/tests/teaching \
  ai-service/tests/api/test_teaching_conversations.py
git commit -m "feat: teach within the active lesson context"
git push origin main
```

---

### Task 4: Vue 课程中心、B 站官方播放器与课内学习页

**Files:**
- Modify: `web/package.json`
- Modify: `web/package-lock.json`
- Modify: `web/src/app/router.ts`
- Modify: `web/src/components/AppShell.vue`
- Modify: `web/src/modules/dashboard/DashboardView.vue`
- Create: `web/src/types/course.ts`
- Create: `web/src/services/course.ts`
- Create: `web/src/modules/course/CourseCatalogView.vue`
- Create: `web/src/modules/course/CourseDetailView.vue`
- Create: `web/src/modules/course/LessonView.vue`
- Create: `web/src/modules/course/components/BilibiliPlayer.vue`
- Create: `web/src/modules/course/components/LessonBlockRenderer.vue`
- Create: `web/src/modules/course/components/LessonTutorPanel.vue`
- Create: `web/src/modules/course/components/LessonProgressRail.vue`
- Create: `web/src/modules/course/*.spec.ts`
- Modify: `infra/nginx/default.conf`
- Test: `web/src/modules/course/components/BilibiliPlayer.spec.ts`
- Test: `web/src/modules/course/LessonView.spec.ts`

- [ ] **Step 1: 写播放器安全、原页面回退和学习页失败测试**

```ts
it('uses the official Bilibili external player and keeps the original link', () => {
  const wrapper = mount(BilibiliPlayer, {
    props: {
      bvid: 'BV14z4y1N7pg',
      page: 15,
      title: '注册接口',
    },
  })
  expect(wrapper.get('iframe').attributes('src')).toBe(
    'https://player.bilibili.com/player.html?bvid=BV14z4y1N7pg&p=15&autoplay=0&danmaku=0',
  )
  const link = wrapper.get('[data-test="open-bilibili"]')
  expect(link.attributes('href')).toBe(
    'https://www.bilibili.com/video/BV14z4y1N7pg?p=15',
  )
  expect(link.attributes('target')).toBe('_blank')
  expect(link.attributes('rel')).toBe('noopener noreferrer')
})

it('rejects an invalid bvid instead of interpolating it into iframe src', () => {
  expect(() => buildBilibiliUrls('javascript:alert(1)', 1)).toThrow()
})

it('does not mark the lesson complete before practice is finished', async () => {
  await wrapper.get('[data-test="video-complete"]').trigger('click')
  expect(wrapper.text()).toContain('还需完成讲义和课时测验')
})
```

- [ ] **Step 2: 运行测试并确认失败**

Run:

```bash
cd web
npm test -- --run src/modules/course
```

Expected: FAIL，课程组件和路由不存在。

- [ ] **Step 3: 实现课程 API、路由和导航**

增加路由：

```text
/courses
/courses/:slug
/lessons/:lessonId
```

侧边栏“学习”的第一项改为：

```ts
{ to: '/courses', icon: '🎓', label: '课程学习' }
```

工作台第一张主卡显示 `GET /api/courses/continue` 的结果，主按钮为“继续学习”；
计划和今日任务保留为辅助入口。

- [ ] **Step 4: 实现官方播放器、回退链接和讲义渲染**

播放器 URL 构造器：

```ts
const BVID_PATTERN = /^BV[0-9A-Za-z]{10}$/

export function buildBilibiliUrls(bvid: string, page: number) {
  if (!BVID_PATTERN.test(bvid) || !Number.isInteger(page) || page < 1) {
    throw new Error('无效的 B 站课程定位')
  }
  return {
    embed: `https://player.bilibili.com/player.html?bvid=${bvid}&p=${page}&autoplay=0&danmaku=0`,
    original: `https://www.bilibili.com/video/${bvid}?p=${page}`,
  }
}
```

iframe 和回退链接固定属性：

```html
<iframe
  :src="urls.embed"
  :title="title"
  sandbox="allow-scripts allow-same-origin allow-presentation allow-popups"
  allow="autoplay; fullscreen; picture-in-picture"
  referrerpolicy="strict-origin-when-cross-origin"
  allowfullscreen
/>
<a
  :href="urls.original"
  target="_blank"
  rel="noopener noreferrer"
  data-test="open-bilibili"
>播放器不可用？前往 B 站原页面</a>
```

安装 `marked` 与 `dompurify`，所有 Markdown 先渲染再消毒；不使用 `v-html` 渲染未经
DOMPurify 处理的模型或资料文本。Nginx CSP 增加：

```text
frame-src 'self' https://player.bilibili.com;
```

- [ ] **Step 5: 实现课内导师和进度交互**

课时页布局：

```text
左侧：章节目录和完成状态
中间：视频、站内讲义、项目代码、检查题、来源
右侧：当前课时 AI 导师
```

用户主动点击“本段视频已学习”、读完讲义或通过检查题时调用
`PUT /api/lessons/{lessonId}/progress`。按钮提交期间禁用；失败时保留本地状态并提示
重试，不显示伪成功。

- [ ] **Step 6: 运行前端验证**

Run:

```bash
cd web
npm test -- --run
npm run typecheck
npm run build
```

Expected: Vitest、类型检查和生产构建全部通过。

- [ ] **Step 7: 提交**

```bash
git add web/package.json web/package-lock.json web/src infra/nginx/default.conf
git commit -m "feat: make course learning the primary experience"
git push origin main
```

---

### Task 5: 课时检查题、测验与掌握度桥接

**Files:**
- Create: `backend/src/main/resources/db/migration/V23__link_quizzes_to_lessons.sql`
- Create: `backend/src/main/java/com/moxiao/studypilot/course/api/SubmitLessonCheckpointRequest.java`
- Create: `backend/src/main/java/com/moxiao/studypilot/course/api/LessonCheckpointResult.java`
- Modify: `backend/src/main/java/com/moxiao/studypilot/course/application/CourseLearningService.java`
- Modify: `backend/src/main/java/com/moxiao/studypilot/assessment/infrastructure/QuizEntity.java`
- Modify: `backend/src/main/java/com/moxiao/studypilot/assessment/api/InternalQuizController.java`
- Modify: `backend/src/main/java/com/moxiao/studypilot/assessment/application/QuizService.java`
- Modify: `ai-service/app/assessment/models.py`
- Modify: `ai-service/app/assessment/service.py`
- Modify: `web/src/modules/course/LessonView.vue`
- Test: `backend/src/test/java/com/moxiao/studypilot/course/api/LessonPracticeWorkflowTest.java`
- Test: `backend/src/test/java/com/moxiao/studypilot/assessment/api/LessonQuizWorkflowTest.java`
- Test: `ai-service/tests/assessment/test_service.py`
- Test: `web/src/modules/course/LessonView.spec.ts`

- [ ] **Step 1: 写确定性检查题与课时测验失败测试**

```java
@Test
void checkpointAnswerIsHiddenUntilSubmissionAndGradedDeterministically() {
    var lesson = getLesson("lesson-rest-controller", ownerToken);
    assertThat(lesson.toString()).doesNotContain("correctOption");

    var result = post(
        "/api/lessons/lesson-rest-controller/checkpoints/checkpoint/attempts",
        ownerToken,
        """
        {"selectedOption":0}
        """
    );
    assertThat(result.body().correct()).isTrue();
    assertThat(result.body().explanation()).contains("DTO");
}

@Test
void lessonQuizUsesTheLessonInsteadOfRequiringATask() {
    var quiz = generateQuiz("""
        {"lessonId":"lesson-rest-controller","webSearch":"AUTO"}
        """);
    assertThat(quiz.lessonId()).isEqualTo("lesson-rest-controller");
    assertThat(quiz.taskId()).isNull();
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run:

```bash
cd backend
./mvnw -Dtest=LessonPracticeWorkflowTest,LessonQuizWorkflowTest test

cd ../ai-service
.venv/bin/pytest -q tests/assessment/test_service.py
```

Expected: FAIL，测验目前只支持 taskId。

- [ ] **Step 3: 扩展数据库与请求契约**

```sql
ALTER TABLE quizzes ADD COLUMN lesson_id VARCHAR(80) NULL;
ALTER TABLE quizzes
    ADD CONSTRAINT fk_quizzes_lesson FOREIGN KEY (lesson_id) REFERENCES lessons(id);
CREATE INDEX idx_quizzes_owner_lesson ON quizzes (owner_id, lesson_id);
```

生成请求必须满足 `taskId` 与 `lessonId` 恰好一个非空；两者都空或同时存在返回 400。
Python 通过 Java 内部课时上下文读取当前课时、来源和相关掌握度，继续复用阶段 7 的
5 题生成、代码文本评估和来源校验。

- [ ] **Step 4: 将练习结果接入进度和掌握度**

- 检查题答对后记录 `practiceCompleted=true` 的资格，但课时最终完成仍要求 5 题测验
  达到 60 分。
- 选择题继续由 Java 确定性判分，编程题继续由 DeepSeek 文本评估，不执行代码。
- 测验知识点写入现有 QUIZ 掌握度证据。
- 低于 60 分时保持 `IN_PROGRESS`，AI 导师收到薄弱知识点并优先解释；达到 60 分后
  自动完成课时并让“继续学习”指向下一课。

- [ ] **Step 5: 运行局部测试**

Run:

```bash
cd backend
./mvnw -Dtest=LessonPracticeWorkflowTest,LessonQuizWorkflowTest,QuizWorkflowTest test

cd ../ai-service
.venv/bin/pytest -q tests/assessment tests/teaching

cd ../web
npm test -- --run src/modules/course
```

Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/resources/db/migration/V23__link_quizzes_to_lessons.sql \
  backend/src/main/java/com/moxiao/studypilot/course \
  backend/src/main/java/com/moxiao/studypilot/assessment \
  backend/src/test/java/com/moxiao/studypilot/course \
  backend/src/test/java/com/moxiao/studypilot/assessment \
  ai-service/app/assessment ai-service/tests/assessment \
  web/src/modules/course
git commit -m "feat: close the lesson practice and mastery loop"
git push origin main
```

---

### Task 6: 真实端到端教学验收与产品文档

**Files:**
- Create: `docs/course-learning-e2e.http`
- Create: `docs/course-learning-e2e-result.md`
- Modify: `README.md`
- Modify: `docs/studypilot-product-requirements.md`
- Modify: `docs/前端开发对接说明.md`
- Modify: `项目开发步骤.md`

- [ ] **Step 1: 编写独立联调脚本**

`docs/course-learning-e2e.http` 覆盖：

```text
登录
→ 查询课程中心
→ 打开第一节课并确认答案未泄露
→ 验证 B 站 BV14z4y1N7pg 第 15/16 分 P 官方播放器和原页面回退
→ 标记视频和讲义进度
→ 创建课内 AI 导师会话
→ 围绕 DTO、Controller 和参数校验连续追问
→ 提交检查题
→ 从课时生成 5 题测验
→ 提交选择题与代码文本
→ 等待代码评估
→ 查询掌握度
→ 完成课时
→ “继续学习”返回下一节
```

- [ ] **Step 2: 运行全量自动化验证**

Run:

```bash
cd backend
./mvnw test

cd ../ai-service
.venv/bin/pytest -q
.venv/bin/ruff check .

cd ../web
npm test -- --run
npm run typecheck
npm run build

cd ..
git diff --check
```

Expected: Java、Python、Vue、Ruff、类型检查、构建和差异检查全部通过。

- [ ] **Step 3: 真实运行验收**

按 MySQL → Spring Boot → FastAPI → Vue 顺序启动，使用当前账号完成整节课。验收结果
记录：

```text
课程和课时 ID
B 站官方播放器与原页面回退链接
DeepSeek provider/model
课内连续对话关键响应
检查题与测验分数
掌握度变化
课时完成时间
下一课返回结果
已知限制
```

不得把 DeepSeek、Tavily、数据库或内部服务秘密写入结果文档。

- [ ] **Step 4: 更新产品主入口**

README 和产品需求将 StudyPilot 定义为“Java + AI 交互式学习平台”；任务、计划和监督
降级为教学辅助。`项目开发步骤.md` 新增阶段 9 六个步骤、测试数量、真实验收和提交。

- [ ] **Step 5: 精确提交并推送**

不暂存用户本地修改：

```text
backend/src/main/resources/application.properties
docs/agent-api-examples.http
```

提交：

```bash
git add docs/course-learning-e2e.http docs/course-learning-e2e-result.md \
  README.md docs/studypilot-product-requirements.md \
  docs/前端开发对接说明.md 项目开发步骤.md
git commit -m "test: complete interactive course learning workflow"
git push origin main
```

---

## Acceptance Criteria

- 登录后的第一核心入口是“继续学习”，不是任务监督。
- 用户可以在课时页内观看黑马原课程，并能随时打开 B 站原页面。
- B 站播放器不可用时有明确回退，不将加载失败伪装成课程完成。
- 打开课程路线、讲义和已有链接时不调用 Tavily 或 DeepSeek。
- 只有主动使用课内导师、搜索、生成测验或代码评估时才调用外部 AI API。
- 每节课同时提供站内讲义、真实项目代码、官方来源、课内 AI 导师和练习。
- AI 导师始终知道当前课程、课时、进度、可见历史和薄弱知识点。
- AI 不声称看过视频，不复制或托管视频/字幕，不把模型常识伪装成黑马课程原话。
- 用户完成视频、讲义和测验后，课时进度与掌握度真实更新，并能继续下一节。
- Java 是课程和学习进度事实中心；浏览器仍不访问 Python `/internal/**`。
- 所有课程、进度、导师会话和测验均按 owner 隔离。

## Known V1 Limitations

- StudyPilot 不读取 B 站 iframe 的观看进度，V1 使用用户主动确认。
- 第一批只精心制作一节示范课；验证教学体验后再扩充九阶段课程，避免批量生成低质量内容。
- AI 只评估代码文本，不编译运行；安全代码沙箱属于后续增强。
- 不抓取 B 站字幕或评论；用户若拥有合法课程讲义，可通过现有资料上传功能补充。
- 课程内容更新采用版本化资源导入，不提供多人课程编辑后台。
