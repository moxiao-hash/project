# Task Execution Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完成任务操作的预览、逐次确认、幂等执行、审计与真实端到端联调。

**Architecture:** Java 增加任务状态修改的治理类型和范围；Python 使用独立
LangGraph 在识别后注册高风险执行，并通过 interrupt 暂停。专用 confirm 接口恢复
图后调用 Java 幂等状态工具，所有非确认消息只能查询、澄清或修改预览。

**Tech Stack:** Java 17、Spring Boot、Python 3.12、FastAPI、LangGraph、DeepSeek、
MySQL、pytest、JUnit

---

## 4.5 操作预览、授权与确认

- [x] Java 增加 `TASK_STATUS_CHANGE` 和 `TASK_MANAGEMENT` 治理枚举。
- [x] Java 契约测试验证高风险任务操作等待确认并写入审计。
- [x] Python 增加任务会话状态、camelCase API DTO 和高风险执行请求。
- [x] LangGraph 完成识别、注册执行、interrupt、修改预览和确认执行。
- [x] 未确认不调用任务 PATCH；重复确认不重复执行。
- [x] 409 版本冲突写入 FAILED 并可通过 GET 查询。
- [x] FastAPI 暴露创建、消息、查询和确认四个内部接口。
- [x] Java 34 个测试、Python 73 个测试和 Ruff 全部通过。
- [x] 提交并推送 `feat: execute confirmed task actions`（`4726994`）。

## 4.6 端到端打卡联调

- [x] 增加独立 `docs/task-agent-e2e.http`，不修改用户已有 HTTP 文件。
- [x] 增加跨层自动化闭环测试和任务执行客户端契约测试。
- [x] 启动 Spring Boot local Profile，确认 MySQL/Flyway/健康检查正常。
- [x] 使用真实 DeepSeek 完成任务查询、识别、预览和确认。
- [x] 查询 Java 任务、任务历史、AgentExecution 和审计日志。
- [x] 重复确认，验证版本和历史记录不再增加。
- [x] 记录真实联调结果、运行命令和内存会话限制。
- [x] 运行 Java/Python 最终全量验证：Java 34、Python 74、Ruff 全部通过。
- [x] 提交并推送 `test: complete task agent end-to-end workflow`。

真实联调记录（2026-07-26）：

- MySQL schema V11，无需迁移；Spring Boot local Profile 正常启动。
- DeepSeek 返回的任务操作经过确定性校验后进入 `PREVIEW_READY`。
- 确认前任务为 `TODO/version 1`；确认后为 `COMPLETED/version 2` 且存在完成时间。
- `task_changes` 数量为 1，AgentExecution 为 `SUCCEEDED`。
- 审计动作包含创建、确认和执行状态更新；重复确认后版本和历史数量保持不变。
- Java 启动时需要与 FastAPI 一样加载 `INTERNAL_SERVICE_TOKEN`，否则内部接口返回
  401。
- 会话仍由内存 Checkpointer 保存，服务重启会丢失。
