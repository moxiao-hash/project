# Task 31 验证证据：前端真实 Token、用量、价格与每日预算可观测性

- **执行 Agent**：ZCode (Gemini 3.8 Flash)
- **测试等级**：`[UNIT_TEST]` / `[STATIC_VALIDATION]`
- **执行时间**：2026-09-21 15:35:00 (Asia/Shanghai)
- **关联分支**：`agent/zcode-task-31-usage-health`
- **关联基础提交**：`59a3578` (origin/main)
- **交付状态**：**待 Codex 验收**（仅改动 `web/**` 与本验证文档，未碰触任何后端、Task 32 代码或未跟踪文件）

---

## 1. 任务背景与职责范围

依据 `docs/superpowers/plans/2026-09-08-agent-productization-and-multi-agent-collaboration.md` 中 Task 31 的职责划分：
- **DeepSeek Harness**：负责后端用量采集、价格目录、每日预算拦截与持久化治理；
- **Codex**：负责计费口径审核、安全与隐私审查、集成验收；
- **ZCode**：负责前端 `AssistantHealthView.vue`、类型定义 `web/src/types/assistant.ts` 及单元测试 `AssistantHealthView.spec.ts` 的全套可观测性界面实现，彻底关闭 Task 20 遗留的可观测性与健康页面缺口。

---

## 2. 核心功能与设计实现

1. **分模型用量与明细看板 (`ModelUsageDetail`)**：
   - 支持按模型维度（`modelId`）展示调用次数、Prompt Tokens、Completion Tokens 及 Reasoning Tokens（思考过程用量）；
   - 金额严格依据官方定价目录核算，对于未登记价格或价格未知的模型，**严禁记为 0 成本，必须明确标为“不可估算”**（带有专属黄色徽标 `data-testid="unestimated-badge"`）。
2. **延迟可观测性与分位数指标**：
   - 扩展平均耗时卡片，在具备分位数采样时同时呈现 **P50 延迟** 与 **P95 延迟** 指标，直观反映真实网络与模型响应分布。
3. **用户每日预算与硬限制进度 (`UserBudgetStatus`)**：
   - 增加每日调用配额进度条（`dailyCallsUsed / dailyCallsLimit`）与每日花费限额进度条（`dailyCostUsed / dailyCostLimit`），提供超限视觉警示（`exceeded` 变红）；
   - 当 `budget.budgetExhausted` 为真时，顶部渲染醒目的预算告警横幅（`data-testid="budget-alert"`），提供明确的降级解释：“今日调用次数或费用已达到上限，纯 Java 查阅与导航仍可使用，次日 0 点重置”。
4. **隐私安全防线与底线保护**：
   - 界面严格遵循审计与隐私安全底线，仅展示统计计量与模型标识，**严禁透传、回显或渲染任何用户 Prompt 明文、请求 Headers、API Key 或认证凭据**。

---

## 3. 运行环境与前置状态

- **操作系统**：macOS darwin arm64
- **Node 环境**：v26.5.0 / npm 11.17.0
- **测试框架**：Vitest v3.2.7 (jsdom 环境)
- **类型检查器**：vue-tsc
- **构建工具**：Vite v7.3.6
- **代码仓库路径**：`/Users/moxiao/IdeaProjects/project`

---

## 4. TDD 闭环验证事实

### 4.1 单元测试证据
执行命令：`cd web && npm test -- src/modules/assistant/AssistantHealthView.spec.ts`
```text
 ✓ src/modules/assistant/AssistantHealthView.spec.ts (7 tests) 47ms

 Test Files  1 passed (1)
      Tests  7 passed (7)
```
测试用例覆盖范围：
1. `未采集的费用和用量显示暂无数据`
2. `汇总指标展示成功率、总 Token 和估算费用（含 reasoning tokens 汇总计算）`
3. `分模型用量明细：正确渲染模型列表、Token 及未知价格的“不可估算”标记`
4. `延迟可观测性：同时展示 P50 与 P95 延迟分位数指标`
5. `用户每日预算展示：正常状态下展示调用配额进度与花费限额`
6. `预算耗尽拦截：当每日预算耗尽时渲染醒目的预算告警与降级提示`
7. `隐私安全防线：页面严禁透传或渲染任何用户 Prompt 明文与敏感凭据`

### 4.2 前端全量测试回归
执行命令：`cd web && npm test -- --run`
```text
 Test Files  23 passed (23)
      Tests  148 passed (148)
   Duration  4.57s
```
全量 23 个测试文件、148 项测试 100% 全部通过，零功能回归。

### 4.3 静态类型检查与生产构建
执行命令：`cd web && npm run typecheck && npm run build`
```text
> studypilot-web@0.1.0 typecheck
> vue-tsc --noEmit

> studypilot-web@0.1.0 build
> vue-tsc --noEmit && vite build
vite v7.3.6 building client environment for production...
transforming...
✓ 264 modules transformed.
rendering chunks...
computing gzip size...
✓ built in 2.14s
```
`vue-tsc` 检查零 error/warning，Vite 生产构建 264 个模块全部成功打包。

### 4.4 格式与空白字符校验
执行命令：`git diff --check`
结果：无任何残余空白字符或格式告警，退出码为 0。

---

## 5. 变更文件清单

- `修改`: `web/src/types/assistant.ts`（补充 `ModelUsageDetail`、`UserBudgetStatus` 及 `AssistantHealth` 扩展字段）
- `修改`: `web/src/modules/assistant/AssistantHealthView.vue`（实现预算告警横幅、P50/P95 延迟、每日预算进度条、模型维度费用明细表）
- `修改`: `web/src/modules/assistant/AssistantHealthView.spec.ts`（新增分模型明细、P50/P95、预算进度、超限拦截及隐私安全防线等 7 项单元测试）
- `新增`: `docs/verification/task-31-frontend.md`（本验证报告）
