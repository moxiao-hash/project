# Internal Daily Task Query Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Python Agent 提供一个受内部令牌保护、按用户和日期隔离的 Java 学习任务查询接口。

**Architecture:** 新接口只做只读查询，不新增数据库表。Controller 接收 `ownerId` 和
ISO 日期，复用现有 `LearningTaskService.list(ownerId, date)`，再映射为已有
`LearningTaskResponse`；Spring Security 已统一保护 `/internal/**`。

**Tech Stack:** Java 17、Spring Boot 4、Spring MVC、Spring Data JPA、MockMvc、JUnit 5

---

## 文件结构

- Create:
  `backend/src/test/java/com/moxiao/studypilot/learning/api/InternalDailyTaskQueryContractTest.java`
  —— 验证内部认证、日期过滤和用户隔离。
- Modify:
  `backend/src/main/java/com/moxiao/studypilot/learning/api/InternalLearningToolController.java`
  —— 暴露只读任务查询入口。
- Modify: `项目开发步骤.md`
  —— 将 4.1 标记为完成并记录验证结果。

### Task 1: 锁定内部今日任务查询契约

**Files:**
- Create:
  `backend/src/test/java/com/moxiao/studypilot/learning/api/InternalDailyTaskQueryContractTest.java`

- [x] **Step 1: 写失败接口测试**

测试创建两个用户。第一个用户创建并确认计划，再创建今天和明天两个任务；第二个用户
也创建今天的任务。调用：

```java
mockMvc.perform(get("/internal/users/{ownerId}/learning-tasks", firstUser.id())
        .queryParam("date", today.toString())
        .header("X-Internal-Service-Token", "test-internal-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].title").value("第一个用户今天的任务"))
        .andExpect(jsonPath("$[0].scheduledDate").value(today.toString()));
```

同一测试还要验证：

```java
mockMvc.perform(get("/internal/users/{ownerId}/learning-tasks", firstUser.id())
        .queryParam("date", today.toString()))
        .andExpect(status().isUnauthorized());

mockMvc.perform(get("/internal/users/{ownerId}/learning-tasks", firstUser.id())
        .queryParam("date", "not-a-date")
        .header("X-Internal-Service-Token", "test-internal-token"))
        .andExpect(status().isBadRequest());
```

- [x] **Step 2: 运行测试并确认 RED**

Run:

```bash
cd backend
./mvnw -Dtest=InternalDailyTaskQueryContractTest test
```

Expected: FAIL，`GET /internal/users/{ownerId}/learning-tasks` 返回 404。

### Task 2: 实现最小只读接口

**Files:**
- Modify:
  `backend/src/main/java/com/moxiao/studypilot/learning/api/InternalLearningToolController.java`

- [x] **Step 1: 注入现有任务服务**

为 Controller 增加字段和构造参数：

```java
private final LearningTaskService taskService;

public InternalLearningToolController(
        InternalLearningContextService contextService,
        LearningPlanService planService,
        ConfirmedLearningPlanService confirmedPlanService,
        LearningTaskService taskService
) {
    this.contextService = contextService;
    this.planService = planService;
    this.confirmedPlanService = confirmedPlanService;
    this.taskService = taskService;
}
```

- [x] **Step 2: 增加日期过滤入口**

```java
@GetMapping("/users/{ownerId}/learning-tasks")
public List<LearningTaskResponse> tasks(
        @PathVariable String ownerId,
        @RequestParam
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
) {
    return taskService.list(ownerId, date).stream()
            .map(LearningTaskResponse::from)
            .toList();
}
```

该方法不能直接访问 Repository，也不能接受调用方传入任意 SQL 条件。

- [x] **Step 3: 运行局部测试并确认 GREEN**

Run:

```bash
cd backend
./mvnw -Dtest=InternalDailyTaskQueryContractTest test
```

Expected: 内部令牌、日期过滤、非法日期和用户隔离全部通过。

### Task 3: 全量验证、更新总览并提交

**Files:**
- Modify: `项目开发步骤.md`

- [x] **Step 1: Java 全量验证**

Run:

```bash
cd backend
./mvnw test
```

Expected: 所有 Java 测试通过，既有用户任务接口不受影响。

- [x] **Step 2: 更新开发步骤文档**

把 4.1 状态改为 `✅ 已完成`，并补充：

```text
关键文件：
- InternalLearningToolController.java
- InternalDailyTaskQueryContractTest.java

验证结果：
- 内部令牌保护通过
- 指定日期过滤通过
- 用户数据隔离通过
- Java 全量测试通过
```

同时把“当前开发位置”移动到 4.2 Java 幂等任务状态工具。

- [x] **Step 3: 安全检查并提交**

Run:

```bash
git diff --check
git status --short
```

只暂存本功能文件和两份开发文档，不暂存用户的
`backend/src/main/resources/application.properties` 与
`docs/agent-api-examples.http`。

Commit:

```bash
git commit -m "feat: expose internal daily task query"
git push origin main
```
