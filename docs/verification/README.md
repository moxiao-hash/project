# StudyPilot 多 Agent 验证证据规范与归档指南

> 本目录用于存放三 Agent（Codex、DeepSeek Harness、ZCode）在各 Task 开发过程中的真实测试证据。
> 每一个功能特性或缺陷修复提交，都必须依据本模板在 `docs/verification/task-<编号>.md` 生成独立证据文件，不得仅在聊天会话中宣称“已完成”。

---

## 1. 证据命名与组织规范

- 统一路径：`docs/verification/task-<编号>.md`（例如 `docs/verification/task-27.md`、`docs/verification/task-28.md`）。
- 协同交接要求：在 `docs/协同开发交接说明.md` 中仅保留核心结论与索引，详细测试输出一律引用本目录下的文件。

---

## 2. 证据等级标签 (Test Level Tag)

每一份证据必须在标题和首段明确标注其真实性等级：

- `[UNIT_TEST]`：纯单元测试，无网络、无外部依赖。
- `[STATIC_VALIDATION]`：静态清单、Schema、路由、格式或文档一致性校验，不代表运行时集成。
- `[MOCK_INTEGRATION]`：内存数据库 (H2)、Mock 上游、局部契约测试。**严禁写成全链路 PASS**。
- `[CONTAINER_STRATEGY]`：隔离 Runner 的协议/沙箱策略测试（无需真实 Docker）。
- `[REAL_RUNNER_CONTAINER]`：在断网 Docker/Podman 容器内的真实编译与测试执行。
- `[REAL_E2E]`：真实 MySQL (3306) + Spring Boot (8080) + FastAPI (8000) + 应用实际配置的真实模型调用 + 数据库数据回查。协作 Agent 使用的模型不等于 StudyPilot 运行时模型。

---

## 3. 证据报告标准模板 (Markdown)

```markdown
# Task <编号> 验证证据：<任务名称>

- **执行 Agent**：<Codex / DeepSeek Harness / ZCode（记录实际 Harness 配置）>
- **测试等级**：<[REAL_E2E] / [MOCK_INTEGRATION] / [UNIT_TEST] / [STATIC_VALIDATION] 等>
- **执行时间**：YYYY-MM-DD HH:mm:ss (Asia/Shanghai)
- **Git 提交**：<commit-hash>
- **关联分支**：<agent/zcode-task-... 或 agent/deepseek-task-...>

---

## 1. 运行环境与前置状态

- **操作系统**：macOS darwin arm64 / Linux x86_64
- **Java 版本**：<粘贴 `java -version` 的实际版本>
- **Python 环境**：<粘贴 `.venv/bin/python --version` 的实际版本>
- **Node 环境**：<粘贴 `node --version` 的实际版本>
- **数据库**：<实际 MySQL/H2 版本与 Flyway 迁移版本；未使用则写“不适用”>
- **服务端口状态**：
  - Spring Boot: http://127.0.0.1:8080 (UP)
  - FastAPI: http://127.0.0.1:8000 (UP)
  - Runner Socket: /tmp/studypilot-runner/runner.sock
- **使用模型**：<deepseek-v4-flash / gemini-3.8-flash / local-stub> (必须记录实际 Model ID)

---

## 2. TDD 闭环证据

### 2.1 失败测试证据 (RED Phase)
- **执行命令**：`<失败测试命令>`
- **预期失败输出**：
```text
<粘贴失败的核心报错信息，证明测试先行而非事后补测>
```

### 2.2 成功测试证据 (GREEN Phase)
- **执行命令**：`<测试通过命令>`
- **成功输出**：
```text
<粘贴成功的测试日志与摘要统计>
```

---

## 3. 业务数据真实性回查 (Data Re-check)

> 如果是写操作，必须提供数据库查询或日志事实，证明业务数据真正落库或发生了版本迭代。

- **检查命令**：`mysql -u studypilot -p -e "SELECT ... FROM ... WHERE ..."`
- **实际查询结果**：
```text
<表格或 JSON 记录，证明如 status 由 TODO 变成 COMPLETED，version 由 1 变成 2>
```

---

## 4. 模型用量与成本统计

- **Prompt Tokens**：<数量>
- **Completion Tokens**：<数量>
- **平均延迟 (Latency)**：<毫秒>
- **估算成本 (Estimated Cost)**：<¥ 金额；价格未知或未调用模型时写“不可估算/不适用”，不得填 0 冒充已计量>

---

## 5. 变更文件清单

- `新增`: path/to/new_file
- `修改`: path/to/modified_file
- `测试`: path/to/test_file

---

## 6. 未覆盖项与已知限制 (Uncovered Items & Limitations)

1. <明确写出本次测试未包含的边界，例如未覆盖高并发网络抖动、未测试非 UTF-8 编码文件等>
2. <环境约束与前提条件>

---

## 7. 下一步交接建议

<说明下一位接手的 Agent 或 Codex 验收时需要关注的关键点>
```
