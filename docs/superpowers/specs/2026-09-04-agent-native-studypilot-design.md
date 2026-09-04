# StudyPilot Agent 原生应用设计

## 1. 目标

StudyPilot 从“多个页面各自带 AI 功能”演进为“统一 Agent 是主要入口、传统页面是可靠备用入口”的 Java + AI 学习操作系统。用户用自然语言表达目标，系统负责读取真实上下文、调用受治理工具并驱动前端导航；涉及业务写入、本地代码或 Git 的操作必须经过 Java 风险策略。

本设计不会让模型拥有数据库、任意 HTTP、Shell、Java 反射或浏览器脚本权限。模型只能从版本化工具目录选择类型化工具，Java 重新校验登录用户、实体归属、参数、风险、授权、幂等和并发版本。

## 2. 信任边界

```text
Vue /assistant
  │ Bearer Token + SSE
  ▼
Spring Boot Agent Facade
  │ internal token
  ▼
Python LangGraph Supervisor
  │ typed tool invocation
  ▼
Spring Boot Business Tool Gateway
  │ signed envelope + Unix socket
  ▼
Container Local Runner
```

- Vue 只调用 Java `/api/**`，不能读取内部服务令牌或直连 FastAPI、Runner。
- Java 是用户身份、核心业务数据、权限、授权、执行记录和审计的事实中心。
- Python 负责理解、检索和规划，不直接写 MySQL，不直接访问 Runner。
- Runner 只接受 Java 的短期签名信封；默认断网、清空环境并限制工作区、CPU、内存、进程、时间和输出。
- DeepSeek、Tavily、资料正文和用户输入均是不可信数据，不能改变工具权限或成为可执行指令。

## 3. 统一会话

公共接口固定为：

```text
POST /api/assistant/conversations
GET  /api/assistant/conversations/{id}
POST /api/assistant/conversations/{id}/messages
GET  /api/assistant/conversations/{id}/events
POST /api/assistant/conversations/{id}/actions/{actionId}/confirm
POST /api/assistant/conversations/{id}/actions/{actionId}/reject
POST /api/assistant/conversations/{id}/turns/{turnId}/cancel
```

创建会话和消息均不接受 `ownerId`。消息需要稳定幂等键，前端界面上下文仅作为提示，Java 必须重新验证其中的实体归属。会话快照返回公开消息、工具过程摘要、动作预览、界面动作、引用、警告、模型、延迟、Token、估算成本和最后事件序号，不返回模型内部思维链。

每轮最多 8 次工具调用、1 次联网搜索、1 个写事务和 1 个待确认高风险动作；同一工具和参数不得连续重复。

## 4. 工具与风险

工具描述的机器事实位于 `backend/src/main/resources/agent-contracts/tool-contract.json`。工具处理器由 Java 显式注册，不使用反射或模型提供的 Bean、类、SQL、URL、Shell。

| 效果 | 默认行为 | 示例 |
|---|---|---|
| `READ` | 自动执行 | 查询今日节点、错题数量、AI 配置状态 |
| `NAVIGATE` | 自动执行 | 打开节点、测验或错题页 |
| `WRITE` | 按风险和授权执行 | 生成测验、调整计划、更新设置 |
| `LOCAL` | 受 Runner 策略控制 | 读取代码、应用补丁、运行测试、Git |

风险为 `NONE / LOW / HIGH`。高风险始终走专用确认接口；普通聊天中的“确认”不能代替确认接口。删除 AI 凭据、依赖准备、应用代码补丁、Git commit 和 push 固定为逐次确认。

Agent 不能代替用户提交答案、编造学习总结、最终接受实践成果或创建/扩大授权。学习真实性边界和每个页面的能力定义在 `capability-matrix.json`。

## 5. 前端动作

Java 只向 Vue 发送 `NAVIGATE / OPEN_MODAL / PREFILL_FORM / REFRESH_RESOURCE / FOCUS_ELEMENT`。导航使用固定 `routeKey`，聚焦使用固定 element key；禁止任意 URL、JavaScript、HTML 和 CSS selector。动作失败时保留会话并展示人工入口，不把 UI 失败误报为业务成功。

## 6. 流式事件

SSE 事件使用每会话严格递增的 `sequence`，支持 `Last-Event-ID` 恢复。事件只公开开始、上下文已加载、工具开始/成功、动作预览、回答增量、界面动作、完成/失败和心跳。断线重连不得重新执行已经完成的工具副作用。

## 7. 主动自动化

自动化规则由用户在专用设置页创建和修改；Agent 不能自行扩权。规则、租约、幂等和执行结果持久化到 MySQL。低风险操作只有在长期授权范围内才能自动执行；高风险仅创建待确认通知。系统提供暂停全部自动化的总开关。

## 8. Developer Agent

能力按读取 → 搜索 → 诊断 → 补丁预览 → 用户确认 → 容器测试 → diff → commit 确认 → push 确认逐级开放。任何文件操作都限制在已登记真实工作区，拒绝目录穿越、软链接逃逸、密钥文件和文件版本冲突。Commit 与 push 是两个独立高风险动作。

## 9. 降级原则

- Python 不可用：传统 Java 页面继续工作。
- DeepSeek 不可用：历史内容、路线、作答和确定性业务操作继续可用。
- Tavily 不可用：本地资料检索继续，并明确提示联网降级。
- Runner 不可用：允许生成建议和补丁预览，但不能声称已经修改或测试。
- SSE 断线：按序号恢复，不重复执行。

## 10. 版本化契约

Task 12 的四份契约资源是后续实现的边界，而不是模型提示词：

- `tool-contract.json`：工具描述、效果、风险和单轮预算。
- `ui-action-contract.json`：允许的前端动作和禁止字段。
- `assistant-event-contract.json`：公开 SSE 事件和恢复语义。
- `capability-matrix.json`：页面能力、写操作与学习真实性限制。

契约从版本 1 开始。破坏性修改必须提升版本，并让 Java、Python、Vue 在部署时拒绝不兼容版本。
