# Task 29 合并与真实运行验收（2026-09-10）

当前结论：**通过 Codex 验收，可合入 `main`，下一步为 Task 30。**

## 代码范围

- 后端 `37ac851` 与前端 `1922ea4` 已合并到独立分支
  `codex/task-29-integration`；Codex 在该分支完成审查修正。
- 原执行 Agent 分支未被改写；用户本地配置、`.env`、HTTP 调试文件均未提交。
- 真实联调使用独立 Java `8081`、Python `8001`、独立 SQLite/Qdrant
  派生数据和随机测试账户；原 `8080/8000/5173` 服务未被替换。

## Codex 审查发现与修正

1. `TURN_COMPLETED` 可能先于消息 POST 完整快照到达，前端因把轮次视为终态而
   丢弃快照中的引用、警告和工具结果。新增竞态回归后，改为仅拒绝旧代际、失败
   或取消轮次的迟到响应，正常完成轮次仍合并完整元数据。
2. 取消真实知识流时，原实现只让 `UnifiedToolGateway` 检查取消标记，DeepSeek
   增量不经过该网关，模型仍会继续输出。新增模型流暂停回归，现于每个真实增量
   前检查同一轮次取消标记并中止流。
3. 取消后清空 `activeTurn` 却未写入正式消息历史，刷新会丢失被取消的用户消息。
   消息契约现保存 `turnId/status`，取消终态持久化用户消息、已输出前缀及
   `cancelled` 状态；正常完成消息也保留轮次身份。

## 自动化验证

- Java Maven：379 tests，0 failures/errors/skipped。
- Python pytest：402 passed，1 条已有 Starlette 弃用警告。
- Python Ruff：应用、测试及四个真实验收脚本全部通过。
- Vue Vitest：23 files / 143 tests 全部通过。
- Vue `vue-tsc --noEmit` 与 Vite 生产构建通过。
- `git diff --check` 通过。

## 真实运行证据

- 持续流：完整事件序列 `2..8` 单调且无重复；30 秒心跳实测 33.2 秒收到。
- 断线续传：从序号 5 重连只收到 6、7、8；未重复执行轮次。
- 幂等：相同消息幂等键再次 POST，前后快照完全一致。
- 真实模型：`deepseek-v4-flash` 输出前缀 30 字符后断开，按快照游标续接
  3931 字符 / 1488 个事件；前缀加后缀严格等于最终回答。
- 取消：真实服务收到取消后终态为 `TURN_CANCELLED`，未出现同轮
  `TURN_COMPLETED`；刷新快照仍保留取消消息。
- 重启：轮次运行中强制终止独立 Python 进程，使用同一加密 SQLite 重启后，
  `activeTurn` 被清理、游标推进并产生 `TURN_FAILED(reason=SERVICE_RESTARTED)`。
- 所有测试账户均为随机隔离账户；未输出密码、Bearer Token 或 API Key。

## 已知限制

- 当前事件总线仍为单 Python worker；多 worker/跨进程广播不在 Task 29 范围。
- 首次使用空 FastEmbed 缓存会同步下载模型并短时阻塞健康检查；正式联调复用
  本机已下载缓存。该冷启动行为应在后续运维/Task 34 中优化。
- 导航和确定性工具文案采用确定性分片；真实模型增量用于知识问答链路。

## 可复现脚本

- `scripts/task29-live-review.py`
- `scripts/task29-model-review.py`
- `scripts/task29-cancel-review.py`
- `scripts/task29-restart-review.py`（只允许传入独立验收 Python 的 PID）
