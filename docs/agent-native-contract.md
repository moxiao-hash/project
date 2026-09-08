# StudyPilot Agent 原生契约规范 (v2 冻结版本)

> 版本：2.0.0 (Wave 0 冻结基线)  
> 状态：FROZEN (未经 Codex 批准严禁破坏性变更)  
> 适用：Spring Boot (Tool Gateway/Facade)、FastAPI (Supervisor/Planner)、Vue (Dispatcher)、Local Runner

本文件冻结的是 Task 28～34 的**目标契约**。当前运行时已经实现的子集以源码为准；新增字段或动作只有在 Java、Python、Vue 三端测试同时落地后才可标记为“已实现”。

---

## 1. 模型输入与输出安全禁令 (Hard Prohibitions)

在所有多轮规划与工具调用过程中，模型及客户端**严禁**生成或注入以下内容，Java 与 Python 策略层将实施无条件硬拦截：

1. `ownerId`：严禁模型生成或客户端传入。用户身份只从认证 Bearer Token 中由服务端解析注入。
2. `url` / `webUrl`：除 `materials.web.import` 受白名单协议域名校验外，严禁模型生成任意可执行外链或回调地址。
3. `sql`：严禁模型生成任意 SQL 文本，所有数据库操作必须由 Java 类型化 Repository 封装。
4. `shell` / `command`：严禁自由拼接 Shell 命令。Runner 执行仅允许类型化模板枚举（`MAVEN_TEST` 等），参数受正则严格校验。
5. `cssSelector` / `xpath` / `script`：严禁前端 UI Action 传递任意选择器或脚本，仅允许预注册的白名单 `routeKey` 与安全实体 ID。
6. `beanName` / `className`：严禁反射或指定 Spring Bean。

---

## 2. 核心数据结构与 Schema 定义

### 2.1 AssistantPlan 与 AssistantPlanStep (规划器输出契约)

用于 Task 28 模型规划器结构化输出，每轮最多 8 步，最多 1 个写操作，最多 1 次联网搜索：

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "AssistantPlan",
  "type": "object",
  "additionalProperties": false,
  "required": ["planId", "intent", "confidence", "summary", "steps"],
  "properties": {
    "planId": {
      "type": "string",
      "format": "uuid",
      "description": "全局唯一计划 ID"
    },
    "intent": {
      "type": "string",
      "enum": ["NAVIGATION", "WRONG_QUESTION_REVIEW", "KNOWLEDGE", "PLAN", "TASK", "TEACHING", "DEVELOPER", "CLARIFY", "GENERAL_CHAT"],
      "description": "用户核心意图分类"
    },
    "confidence": {
      "type": "number",
      "minimum": 0.0,
      "maximum": 1.0,
      "description": "意图置信度；低于 0.7 必须触发 CLARIFY 澄清"
    },
    "summary": {
      "type": "string",
      "maxLength": 200,
      "description": "对用户意图与执行路径的单句公开总结"
    },
    "steps": {
      "type": "array",
      "maxItems": 8,
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": ["stepId", "toolName", "arguments", "dependsOn"],
        "properties": {
          "stepId": {
            "type": "string",
            "pattern": "^s[1-8]$"
          },
          "toolName": {
            "type": "string",
            "description": "来自 Java 已发布 catalog 的类型化工具名"
          },
          "arguments": {
            "type": "object",
            "description": "工具入参；必须再次通过对应 ToolDescriptor.inputSchema，严禁包含 ownerId/url/sql/shell 等禁止字段"
          },
          "dependsOn": {
            "type": "array",
            "items": { "type": "string" },
            "description": "所依赖的前序 stepId；必须无环"
          }
        }
      }
    }
  }
}
```

### 2.2 UiAction (白名单界面动作契约)

由 Java 门面向 Vue 发出的前端导航与界面聚焦指令：

```json
{
  "title": "UiAction",
  "type": "object",
  "additionalProperties": false,
  "required": ["type", "routeKey", "params", "reason"],
  "properties": {
    "type": {
      "type": "string",
      "enum": ["NAVIGATE", "OPEN_MODAL", "PREFILL_FORM", "REFRESH_RESOURCE", "FOCUS_ELEMENT"]
    },
    "routeKey": {
      "type": "string",
      "enum": [
        "ASSISTANT", "DASHBOARD", "ASSISTANT_HEALTH", "ROADMAP", "ROADMAP_STAGE", "ROADMAP_MODULE",
        "ROADMAP_NODE", "LEARNING_GOALS", "LEARNING_PLANS", "LEARNING_PLAN", "TODAY",
        "MATERIALS", "MATERIAL_DETAIL", "QUIZ", "QUIZ_ATTEMPT", "WRONG_QUESTIONS",
        "MASTERY", "KNOWLEDGE", "PLAN_ASSISTANT", "TASK_ASSISTANT", "NOTIFICATIONS",
        "AGENT_ACTIVITY", "LEARNING_SETTINGS", "AI_SETTINGS", "WORKSPACE_ARTIFACTS",
        "COURSES", "COURSE_DETAIL", "LESSON"
      ]
    },
    "params": {
      "type": "object",
      "additionalProperties": {
        "type": "string",
        "pattern": "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$"
      },
      "description": "只允许安全的业务实体标识符（UUID 或安全 slug），拒绝任意路径"
    },
    "reason": { "type": "string", "minLength": 1, "maxLength": 200 }
  }
}
```

当前前端 Dispatcher 仅执行 `NAVIGATE`；其余动作类型是 Task 30 的保留值，在对应白名单与测试完成前必须返回“不支持”，不得静默执行。

### 2.3 PendingToolAction (受治理高风险动作卡契约)

当模型提出修改业务数据、依赖安装或代码操作时，系统生成动作卡并持久化至 MySQL，状态置为 `WAITING_CONFIRMATION`：

```json
{
  "title": "PendingToolAction",
  "type": "object",
  "additionalProperties": false,
  "required": ["actionId", "executionId", "toolName", "toolVersion", "riskLevel", "status", "summary", "arguments", "expiresAt"],
  "properties": {
    "actionId": { "type": "string", "format": "uuid" },
    "executionId": { "type": "string", "format": "uuid" },
    "toolName": { "type": "string" },
    "toolVersion": { "type": "integer", "minimum": 1 },
    "riskLevel": { "type": "string", "enum": ["LOW", "HIGH"] },
    "status": {
      "type": "string",
      "enum": ["WAITING_AUTHORIZATION", "WAITING_CONFIRMATION", "READY", "RUNNING", "SUCCEEDED", "FAILED", "REJECTED"]
    },
    "summary": { "type": "string" },
    "arguments": { "type": "object" },
    "result": {},
    "error": { "type": ["string", "null"] },
    "previewDiff": { "type": "string", "description": "Unified Diff 预览（仅代码操作提供）" },
    "expiresAt": { "type": "string", "format": "date-time" }
  }
}
```

### 2.4 AssistantEvent (统一 SSE 事件契约)

用于服务端到浏览器的单向持续流式输出；客户端命令仍通过 HTTP POST 提交。事件支持通过 `Last-Event-ID` 断线续传：

```json
{
  "title": "AssistantEvent",
  "type": "object",
  "additionalProperties": false,
  "required": ["sequence", "type", "conversationId", "payload"],
  "properties": {
    "sequence": { "type": "integer", "minimum": 1, "description": "单会话严格递增序列号" },
    "type": {
      "type": "string",
      "enum": [
        "HEARTBEAT",
        "TURN_STARTED",
        "CONTEXT_LOADED",
        "PLAN_GENERATED",
        "TOOL_STARTED",
        "TOOL_SUCCEEDED",
        "TOOL_FAILED",
        "ACTION_PREVIEW",
        "ASSISTANT_DELTA",
        "UI_ACTION",
        "TURN_COMPLETED",
        "TURN_FAILED",
        "TURN_CANCELLED"
      ]
    },
    "conversationId": { "type": "string", "format": "uuid" },
    "payload": { "type": "object" }
  }
}
```

### 2.5 Usage (用量与成本计量契约)

Task 20 / Task 31 的硬计量标准，每次轮次完成后上报并汇总至 `AssistantHealth`：

```json
{
  "title": "Usage",
  "type": "object",
  "additionalProperties": false,
  "required": ["provider", "modelName", "promptTokens", "completionTokens", "latencyMs", "estimatedCostCny"],
  "properties": {
    "provider": { "type": "string", "minLength": 1 },
    "modelName": { "type": "string", "minLength": 1, "description": "记录上游实际返回的模型 ID，不使用固定枚举猜测" },
    "promptTokens": { "type": "integer", "minimum": 0 },
    "cachedPromptTokens": { "type": ["integer", "null"], "minimum": 0 },
    "completionTokens": { "type": "integer", "minimum": 0 },
    "reasoningTokens": { "type": ["integer", "null"], "minimum": 0 },
    "latencyMs": { "type": "integer", "minimum": 0 },
    "estimatedCostCny": { "type": ["string", "null"], "pattern": "^[0-9]+(\\.[0-9]+)?$", "description": "Decimal 字符串；价格未知时必须为 null，不得伪记为 0" },
    "pricingVersion": { "type": ["string", "null"] }
  }
}
```

---

## 3. 破坏性演进治理规则

1. 当前契约版本固定为 `2.0.0`。
2. 任何新增必填字段、删除已有字段、修改枚举或调整路由映射的行为均属于**破坏性变更**。
3. 破坏性变更必须由 Codex 提出版本升级提案（如 `2.1.0` 或 `3.0.0`），同步更新 Java DTO、Python Pydantic 与前端 TypeScript 类型定义，并在部署阶段强制拒绝不兼容协议版本。
