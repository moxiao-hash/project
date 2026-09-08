# Task 26 验收修正与实际验证结果

执行日期：2026-09-08；修正基线：`568c815`。责任：Codex 本次核验。
**结论：已修复验收契约和新用户上下文 Bug；Task 26 仍部分完成。**

## 1. 撤回旧结论

2026-09-07 版本把 H2 + 固定 HTTP 模拟上游测试写成了完整真实环境验收。
该测试并未调用模型、实际 Runner、Rubric 或 Git；原报告中的这些 PASS 没有相应证据，
本次撤回。历史提交保留，不以文档打勾代替实际运行结果。

## 2. 实际修改与回归

- 测试更名为 `AssistantFacadeContractTest`；修正 READY、pendingAction、uiActions、
  TODAY、params/reason，并补齐动作的 executionId、toolVersion、expiresAt。
- 先把断言改为真实契约，运行测试观察到 READY 预期与 IDLE 实际值不符，再修正模拟响应。
- SSE 门面断言排除已消费事件、保留顺序，验证 afterSequence 与认证 owner 注入。
- Java 治理测试验证真实任务 COMPLETED、completedAt、version=2、history=1，
  AgentExecution SUCCEEDED、完整审计动作，以及重复确认审计不变。
- 重写独立 HTTP 脚本，改用确定性的学习设置 HIGH 风险操作，不再读取不存在的 action 字段。
- 新增公共 API 自动冒烟脚本，脚本只操作新注册演示账户，不接触正式用户或源码工作区。

## 3. 真实联调发现并修复的 Bug

新用户没有路线时，`AgentLearningContextService.get` 在只读事务中调用
`RoadmapQueryService.currentMap`。内层抛出未找到异常，使共享事务标为 rollback-only；
外层即使 catch，也会在提交时报 UnexpectedRollbackException，最终 Agent 消息返回 503。

先新增 `InternalAgentToolControllerTest.newUserWithoutRoadmapReceivesContextWarningInsteadOfTransactionRollback`
复现真实 Spring 事务错误，再新增 Optional 查询 `currentMapIfPresent`。
缺少路线现在返回可恢复提示，不放宽全局回滚规则，原公共路线接口 404 语义保持不变。
原 Mock 单测没有 Spring 事务代理，无法发现此问题，已同步改用 Optional 桩。

## 4. 本次真实环境证据

启动顺序与命令（均从对应目录执行，不写入或输出真实密钥）：

```bash
# backend；MySQL 已在本机运行，local profile 加载已有私有配置
./mvnw -q spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.arguments=--server.address=127.0.0.1
# ai-service；只运行一个进程
.venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
# web
npm run dev -- --host 127.0.0.1 --port 5173 --strictPort
# 仓库根目录
ai-service/.venv/bin/python scripts/agent-native-smoke.py
```

Java /actuator/health 和 Python /health 返回 UP。Java local profile 连接实际 MySQL，
不是 H2；浏览器前端服务可访问，但本次不把 HTTP 200 算作视觉验收。

首次成功记录：
- conversationId：`e1de526a-1ef9-46ee-b501-125c9424bda3`
- executionId：`8a26d94e-1479-489c-8c21-5db796e42147`
- 每日学习时长：60 → 30 分钟；执行 SUCCEEDED。
- 发普通消息“确认”后仍 WAITING_CONFIRMATION，设置仍为 60。
- 专用确认后查回业务设置为 30；重复确认的响应、设置、执行列表、审计列表均保持不变。
- 事件序号 1～14，Last-Event-ID=8 精确返回 9～14；重复读取不新增审计。
- 干净构建并重启 Java 后再次执行通过：conversationId `84e7ea86-ff5e-4eb7-8142-7377c414994b`，executionId `653147b3-5736-4e8e-a9c0-1c2b7f4fbbb5`；前端 HTTP 200、Python UP。
- 会话携带伪造 ownerId 创建后，真实登录用户能读取；公共响应移除 ownerId。
- 模型调用：**无**；Runner 执行：**无**；浏览器交互：**未验证**。
- 演示账户保留作追溯，不保存或输出其随机密码/Token。调试失败轮次也只影响独立演示用户。

该结果证明 Java→Python→Java/MySQL 的确定性业务治理闭环，
**不证明 DeepSeek 回答质量，也不证明容器、Rubric、Git 或完整前端闭环。**

## 5. 本次自动化验证

| 范围 | 命令 | 结果 / 证据层级 |
| --- | --- | --- |
| Java | `cd backend && ./mvnw -q clean test` | 363 项；H2 / Java 集成 / 模拟依赖，不等于 MySQL E2E |
| Python | `cd ai-service && .venv/bin/python -m pytest -q` | 302 项通过；1 条 Starlette 兼容警告 |
| Runner | `cd runner-service && ../ai-service/.venv/bin/python -m pytest -q` | 21 项通过；不能替代实际容器执行 |
| Ruff | `ai-service/.venv/bin/ruff check ai-service/app ai-service/tests runner-service scripts/agent-native-smoke.py` | 通过 |
| Vue | `cd web && npm test -- --run` | 124 项通过，22 个测试文件 |
| 类型与构建 | `cd web && npm run typecheck && npm run build` | 通过 |
| 空白检查 | `git diff --check` | 通过 |

Java 新增 1 个新用户回归测试；更名测试数量不变。曾有两个旧 Mock 单测因为仍模拟
currentMap 而失败，调整为新的 Optional 查询后重跑全量。干净构建用于排除改名前残留报告。

## 6. 未验收清单与真实限制

- [ ] 一次真实 DeepSeek/Tavily/Qdrant 来源问答到 Vue 引用展示的完整链路。
- [ ] 同一轮安全临时工作区的容器测试→源码发送确认→真实 Rubric→用户最终验收。
- [ ] 临时 Git 仓库的补丁→测试→commit 专用确认→push 再确认；不使用真实项目作破坏性样本。
- [ ] Task 25 Playwright/IDE 实际适配器。目前仅有 InterfaceFallbackPolicy 预览策略。
- [ ] SSE 持续实时推送。当前接口返回有限 SSE 格式事件集合，前端轮询重放，
      不是持续模型 token 流；此次仅验证游标恢复。
- [ ] 完整 UI 操作/降级演示记录。
- [ ] Task 20 真实模型 Token、估算计费与预算限制。

不因本次未重跑而否定 Task 22/23 的历史独立验收，但历史成功不能填充上述本次未覆盖项。
下一位开发者应逐项补证据；没有适配器的项目先按 TDD 完成实现，不直接勾选“完成”。
