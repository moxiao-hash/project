# Task 31 集成验收记录

日期：2026-09-21  
验收分支：`codex/task-30-31-acceptance`  
结论：**通过**

## 验收范围

Codex 将 DeepSeek Harness 的 Task 31 后端链与 ZCode 的健康页实现合入独立验收分支，检查真实 usage、价格版本、用户预算、owner 隔离、可观测性展示和隐私边界。Task 30 的已验收实现同时保留，未合并 `main`。

## 独立回归

- Java：482 项通过。
- Python：556 项通过，1 条既有 warning。
- Vue：34 个测试文件、319 项通过。
- Ruff、TypeScript typecheck、生产构建和 `git diff --check` 通过；构建处理 270 个模块。
- MySQL 9.6 启动成功，Flyway 验证 46 个迁移，schema 版本为 46，Hibernate 校验通过。

## 真实模型、数据库与浏览器证据

使用隔离测试用户发起最小 DeepSeek 调用，未记录正文、凭据或请求 Header。实际模型为 `deepseek-v4-flash`，数据库与 `/api/assistant/health` 返回一致：1 次成功调用，输入 35、输出 17、reasoning 15、总 Token 52，估算费用 `0.0000309 USD`，价格版本 `deepseek-pricing-2026-09-20`，P50/P95 延迟均为 1006 ms。

Computer Use 登录真实 Vue 页面 `/assistant/health` 后，页面可见上述模型、Token、费用、价格版本和延迟；reasoning 作为输出明细展示，但未重复计入总 Token。模型遥测与历史治理执行记录分区，后者无样本时如实显示“暂无数据”。

另一次最小调用验证了 reservation 最终化：输入上界 159，实际输入 35、输出 23、reasoning 21、总 Token 58，最终费用 `0.00003810 USD`，reservation 为 `FINALIZED`。owner 冲突返回 409 `ASSISTANT_USAGE_OWNER_CONFLICT`；未知对象和未知价格保持 fail-closed/不可估算，不伪记零成本。

## 安全与边界

- usage 最终化、释放和查询绑定 owner；跨用户不能消费或篡改他人 reservation。
- 每次 provider 调用都经过预算预留与最终化；输入上界采用确定性保守估算。
- 金额使用定点数据库字段并绑定价格版本；历史记录不随新价格重算。
- 健康接口和页面不暴露 Prompt、API Key、请求 Header 或其他用户数据。

## 限制

MySQL 9.6 高于当前 Flyway 官方验证到的 9.4，启动时有兼容性 warning，但本次 46 个迁移均成功验证且 schema 已是最新。该 warning 不影响本次 Task 31 结论。
