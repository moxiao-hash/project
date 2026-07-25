# Idempotent Agent Task Status Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 提供一个受内部令牌保护的 Java 工具，使 Agent 能幂等地完成、跳过或延期
用户任务，并拒绝越权与过期版本操作。

**Architecture:** 内部 Controller 接收 `ownerId`、`idempotencyKey`、`expectedVersion`
和状态变化参数，Application Service 先按幂等键查重，再校验任务归属和版本，最后复用
`LearningTaskEntity.changeStatus` 并写入 `task_changes`。幂等键保存在任务变更表，MySQL
唯一索引作为最终防重边界；普通用户任务接口继续写入空幂等键。

**Tech Stack:** Java 17、Spring Boot 4、Spring MVC、JPA、Flyway、MySQL/H2、MockMvc、JUnit 5

---

## 文件结构

- Create:
  `backend/src/main/resources/db/migration/V11__add_task_operation_idempotency.sql`
  —— 为任务变更增加可空的 Agent 操作幂等键和唯一索引。
- Create:
  `backend/src/main/java/com/moxiao/studypilot/learning/api/InternalChangeTaskStatusRequest.java`
  —— 定义严格的内部写请求。
- Modify:
  `backend/src/main/java/com/moxiao/studypilot/learning/infrastructure/TaskChangeEntity.java`
  —— 映射幂等字段并暴露比较所需 getter。
- Modify:
  `backend/src/main/java/com/moxiao/studypilot/learning/infrastructure/TaskChangeJpaRepository.java`
  —— 按幂等键查询。
- Modify:
  `backend/src/main/java/com/moxiao/studypilot/learning/application/LearningTaskService.java`
  —— 增加幂等状态变化用例并复用现有状态机。
- Modify:
  `backend/src/main/java/com/moxiao/studypilot/learning/api/InternalLearningToolController.java`
  —— 暴露内部 PATCH 工具。
- Create:
  `backend/src/test/java/com/moxiao/studypilot/learning/api/InternalTaskStatusToolContractTest.java`
  —— 验证幂等、版本冲突、归属和延期约束。
- Modify: `项目开发步骤.md` —— 记录完成内容与验证结果。

### Task 1: 用契约测试锁定 Agent 写操作

**Files:**
- Create:
  `backend/src/test/java/com/moxiao/studypilot/learning/api/InternalTaskStatusToolContractTest.java`

- [x] **Step 1: 写完成任务与幂等测试**

先通过现有 HTTP 接口创建用户、已确认计划和一个 `version=1` 的任务，再调用：

```java
String request = """
        {
          "ownerId": "%s",
          "idempotencyKey": "task-action:conversation-1:complete",
          "expectedVersion": 1,
          "status": "COMPLETED",
          "reason": "用户在对话中明确表示已完成"
        }
        """.formatted(ownerId);
```

连续两次执行：

```java
mockMvc.perform(patch("/internal/learning-tasks/{taskId}/status", taskId)
        .header("X-Internal-Service-Token", "test-internal-token")
        .contentType(MediaType.APPLICATION_JSON)
        .content(request))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.completedAt").isNotEmpty());
```

再通过用户历史接口断言只有一条变更：

```java
mockMvc.perform(get("/api/learning-tasks/{taskId}/history", taskId)
        .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
```

- [x] **Step 2: 写版本冲突和越权测试**

使用新的幂等键但继续传 `expectedVersion=1`，当前任务已是 version 2，应返回 409。
使用其他用户 ID 操作任务，应返回 404。

- [x] **Step 3: 写延期约束测试**

`DEFERRED` 必须提供未来日期和非空原因；缺失日期、今天或更早日期均返回 400。

- [x] **Step 4: 运行测试并确认 RED**

Run:

```bash
cd backend
./mvnw -Dtest=InternalTaskStatusToolContractTest test
```

Expected: FAIL，内部 PATCH 路由返回 404。

### Task 2: 增加数据库幂等边界

**Files:**
- Create:
  `backend/src/main/resources/db/migration/V11__add_task_operation_idempotency.sql`
- Modify:
  `backend/src/main/java/com/moxiao/studypilot/learning/infrastructure/TaskChangeEntity.java`
- Modify:
  `backend/src/main/java/com/moxiao/studypilot/learning/infrastructure/TaskChangeJpaRepository.java`

- [x] **Step 1: 增加 Flyway 迁移**

```sql
ALTER TABLE task_changes
    ADD COLUMN operation_idempotency_key VARCHAR(180);

CREATE UNIQUE INDEX uk_task_changes_operation_key
    ON task_changes (operation_idempotency_key);
```

该字段必须允许 `NULL`，使现有用户手动状态接口不需要生成 Agent 幂等键。

- [x] **Step 2: 映射实体字段**

`TaskChangeEntity` 增加：

```java
@Column(name = "operation_idempotency_key", length = 180)
private String operationIdempotencyKey;
```

现有构造器委托给新构造器并传 `null`，保持用户 API 行为不变；新构造器接收幂等键。
增加 `getTaskId()`、`getToStatus()`、`getToScheduledDate()`、
`getReason()` 和 `getOperationIdempotencyKey()`。

- [x] **Step 3: 增加 Repository 查询**

```java
Optional<TaskChangeEntity> findByOperationIdempotencyKey(String key);
```

### Task 3: 实现严格内部请求与幂等用例

**Files:**
- Create:
  `backend/src/main/java/com/moxiao/studypilot/learning/api/InternalChangeTaskStatusRequest.java`
- Modify:
  `backend/src/main/java/com/moxiao/studypilot/learning/application/LearningTaskService.java`

- [x] **Step 1: 定义请求约束**

请求字段：

```java
public record InternalChangeTaskStatusRequest(
        @NotBlank String ownerId,
        @NotBlank @Size(max = 180) String idempotencyKey,
        @Min(1) int expectedVersion,
        @NotNull LearningTaskStatus status,
        LocalDate scheduledDate,
        @Size(max = 255) String reason
) {
}
```

紧凑构造器执行以下规则：

```text
TODO 不允许作为 Agent 操作目标；
DEFERRED 必须提供日期；
SKIPPED 和 DEFERRED 必须提供非空原因；
非 DEFERRED 操作不能携带 scheduledDate。
```

提供 `toStatusRequest()` 转换成已有 `ChangeTaskStatusRequest`。

- [x] **Step 2: 实现幂等状态变化**

Service 方法顺序必须是：

```text
校验 taskId + ownerId
→ 按 idempotencyKey 查重
→ 若存在则校验请求与原操作一致并返回当前任务
→ 校验 expectedVersion
→ 调用实体状态机
→ 写入带 idempotencyKey 的 task_changes
```

版本不一致或同一键对应不同请求时抛 `ConflictException`。

### Task 4: 暴露内部 PATCH 并验证

**Files:**
- Modify:
  `backend/src/main/java/com/moxiao/studypilot/learning/api/InternalLearningToolController.java`

- [x] **Step 1: 增加内部入口**

```java
@PatchMapping("/learning-tasks/{taskId}/status")
public LearningTaskResponse changeTaskStatus(
        @PathVariable String taskId,
        @Valid @RequestBody InternalChangeTaskStatusRequest request
) {
    return LearningTaskResponse.from(
            taskService.changeStatusIdempotently(taskId, request)
    );
}
```

- [x] **Step 2: 运行局部测试**

Run:

```bash
cd backend
./mvnw -Dtest=InternalTaskStatusToolContractTest test
```

Expected: 所有契约测试通过。

- [x] **Step 3: Java 全量测试**

Run:

```bash
./mvnw test
```

Expected: 新旧任务接口和所有既有功能全部通过。

### Task 5: 文档、MySQL 与提交

**Files:**
- Modify: `项目开发步骤.md`
- Modify:
  `docs/superpowers/plans/2026-07-25-idempotent-agent-task-status.md`

- [x] **Step 1: 更新开发总览**

把 4.2 标记为完成，记录接口、幂等行为、版本冲突、关键文件、全量测试数量和提交名；
把当前开发位置移动到 4.3 Python 任务工具客户端。

- [x] **Step 2: MySQL 迁移验证**

使用 `local` Profile 启动 Java，确认 Flyway 从 v10 迁移到 v11，健康检查为 `UP`，
随后正常关闭临时进程。

- [ ] **Step 3: 安全检查、提交和推送**

Run:

```bash
git diff --check
git status --short
```

不得暂存用户修改的 `application.properties` 和 `agent-api-examples.http`。

Commit:

```bash
git commit -m "feat: add idempotent agent task status tool"
git push origin main
```
