# Task 31 验证证据：前端真实模型遥测与健康可观测性对齐（含零行响应与延迟样本解耦）

- **执行 Agent**：ZCode (Gemini 3.8 Flash)
- **测试等级**：`[UNIT_TEST]` / `[STATIC_VALIDATION]`
- **执行时间**：2026-09-21 16:30:00 (Asia/Shanghai)
- **关联分支**：`agent/zcode-task-31-usage-health`
- **关联基础提交**：`59a3578` (origin/main)
- **交付状态**：**待 Codex 验收**（仅修改 `web/**` 与本验证文档，未碰触任何后端、Task 32 代码，未跟踪的 `docs/verification/task-29-codex-review.md` 保持原样未修改）

---

## 1. 契约对齐与模型遥测完全解耦

依据 Codex 针对真实 Wire 响应契约与可观测性真实性的验收意见，前端完成了系统性重构与修复：

| 契约与审查要求 | 修复前偏差 | 修正与防护实现 |
| :--- | :--- | :--- |
| **1. 模型延迟与执行样本彻底解耦** | 原实现使用 `!health.modelCalls \|\| !health.latencySamples` 判断模型延迟，导致在存在模型调用且有 p50/p95 但执行层 `latencySamples=0` 时误判为“暂无数据”（假空状态） | 模型延迟判定逻辑改为 `!health.modelCalls \|\| (!health.p50LatencyMs && !health.p95LatencyMs)`，**仅由模型遥测字段/行自身决定，绝不耦合遗留执行层的 `latencySamples`**；执行层的平均耗时保持使用 `latencySamples` 独立呈现。 |
| **2. 真实零行无数据状态** | 后端无数据返回 `modelCalls=0, models=[], priceStatus=NONE, usageEstimatedCost=0, currency=null, p50=0, p95=0` 时，前端错误渲染为 `0 USD`、`0 ms` 及 `按官方目录精确核算`，把未采集当成测量零 | 当 `priceStatus === 'NONE'` 或 `!health.modelCalls` 时，估算费用卡片和延迟分位卡片明确显示 `暂无数据`，副标题展示 `暂无已计价调用` 与 `暂无延迟采样数据`；绝不渲染 `0 USD`、`0 ms`，也不展示 `价格未知` 徽标（严格区分无样本 `NONE` 与未知模型价格 `UNKNOWN`）。 |
| **3. 文案调整为估算核算** | 提示文案使用“精确核算”等绝对化词汇 | 统一替换为“按官方目录估算核算”与“基于官方目录估算的费用”，严格声明金额仅供估算参考。 |
| **4. 指标分区独立呈现** | 模型指标与执行指标混杂在同一卡片网格 | 彻底划分为两大独立区块：`[data-testid="model-telemetry-section"]`（真实模型调用与用量可观测性）与 `[data-testid="legacy-execution-section"]`（历史执行记录与治理统计）。 |
| **5. Token 不重复计数** | 前端自算并对 `modelTotalTokens` 与 `reasoningTokens` 二次相加 | 直接使用后端已准确聚合的 `health.modelTotalTokens` 及每行模型的 `m.totalTokens`，**绝不在前端再次叠加 `reasoningTokens`**，同时完整展示缓存命中、未缓存、输出与思考分项。 |
| **6. 动态货币与移除硬编码 ¥** | 界面硬编码包含 `¥` 人民币符号 | 彻底移除模板与样式中的每一个 `¥` / `￥` 符号，依据后端返回的 `currency` 动态展示（通常为 `USD`），如 `0.0425 USD`。 |
| **7. 未知价格显式表达** | 未知价格模型误显为 0 或 0.00 | 当且仅当 `priceStatus === 'UNKNOWN'` 或 `estimatedCost === null` 且有调用时，显式展示专属黄色未知徽标（`data-testid="cost-unknown-badge"` 提示“价格未知”），并展示 `含 N 次未计价调用`，绝不以 0 冒充零成本。 |
| **8. 真实维度与字段对齐** | 使用了自造字段 `modelId`、`callCount`、`isEstimated` | 对齐真实字段 `modelName`、`provider`、`calls`、`failedCalls`、`failureRate`、`cachedPromptTokens`、`uncachedPromptTokens`、`completionTokens`、`reasoningTokens`、`totalTokens`、`p50LatencyMs`、`p95LatencyMs`、`priceStatus`、`priceVersion`。 |
| **9. 移除虚构 Budget UI** | 页面包含了自造的 budget 进度条和超限横幅 | 彻底移除公开健康接口不存在的 `budget` 字段及全部相关组件（`budget-alert`、`budget-card`）。 |
| **10. 诚实空状态** | 无模型调用时缺少真实空状态 | 当 `models` 为空时，展示 `[data-testid="models-empty-state"]`（提示“暂无模型调用数据”）。 |

---

## 2. 运行环境与前置状态

- **操作系统**：macOS darwin arm64
- **Node 环境**：v26.5.0 / npm 11.17.0
- **测试框架**：Vitest v3.2.7 (jsdom 环境)
- **类型检查器**：vue-tsc
- **构建工具**：Vite v7.3.6
- **代码仓库路径**：`/Users/moxiao/IdeaProjects/project`

---

## 3. TDD 闭环验证事实

### 3.1 RED 阶段失败证据
在补充针对延迟解耦的两个 RED 用例后执行测试，精确捕获到用例 (a) 因耦合 `latencySamples` 导致的失败日志：
```text
FAIL src/modules/assistant/AssistantHealthView.spec.ts
× RED用例 (a)：modelCalls>0 且具备正值 p50/p95 延迟时，即使 legacy latencySamples=0 也必须正常展示模型延迟
  AssertionError: expected '模型延迟分位 暂无数据  暂无延迟采样数据' not to contain '暂无数据'
  Expected: "暂无数据"
  Received: "模型延迟分位 暂无数据  暂无延迟采样数据"
```

### 3.2 GREEN 阶段成功证据
解耦模型延迟判定逻辑后执行：`cd web && npm test -- src/modules/assistant/AssistantHealthView.spec.ts`
```text
✓ src/modules/assistant/AssistantHealthView.spec.ts (10 tests) 50ms

Test Files  1 passed (1)
     Tests  10 passed (10)
```
测试用例清单（10 项全数通过）：
1. `模型遥测与执行记录分立展示：独立呈现模型可观测指标与执行层统计`
2. `Token 用量直接取后端 modelTotalTokens，绝不二次叠加 reasoningTokens`
3. `费用展示动态货币符号（通常为 USD），严禁出现硬编码人民币符号 ¥`
4. `当 priceStatus 为 UNKNOWN 或金额为 null 时，显式展示未知状态，绝不记为 0`
5. `无模型调用行时，真实呈现无数据空状态`
6. `分模型详细指标展示：覆盖失败次数、缓存/未缓存 Prompt、P50/P95 与定价版本`
7. `彻底移除虚构的 budget UI，公开健康接口不包含 budget 字段`
8. `真实零行响应：modelCalls=0, models=[], priceStatus=NONE, usageEstimatedCost=0, currency=null, p50LatencyMs=0, p95LatencyMs=0 显示暂无数据而非 0 USD/0 ms/价格未知`
9. `RED用例 (a)：modelCalls>0 且具备正值 p50/p95 延迟时，即使 legacy latencySamples=0 也必须正常展示模型延迟`
10. `RED用例 (b)：modelCalls=0 且模型未采样时，即使 legacy latencySamples>0 也必须展示模型暂无数据`

### 3.3 全量前端测试套件回归
执行命令：`cd web && npm test -- --run`
```text
Test Files  23 passed (23)
     Tests  151 passed (151)
  Duration  7.33s
```
全量 23 个测试文件、151 项测试 100% 全部通过，零功能回归。

### 3.4 静态类型检查与生产构建
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
✓ built in 2.02s
```
`vue-tsc` 类型检查零错误，生产打包 264 个模块全部成功。

### 3.5 代码格式与空白字符校验
执行命令：`git diff --check`
结果：无任何警告或输出，退出码为 0。

---

## 4. 变更文件清单

- `修改`: `web/src/modules/assistant/AssistantHealthView.vue`（彻底解耦模型延迟与 legacy latencySamples；区分 NONE/UNKNOWN/KNOWN；零行响应呈现诚实暂无数据；替换精确核算文案为估算核算；移除所有硬编码 ¥ 符号；直接使用 `modelTotalTokens`；渲染模型明细表与空状态）
- `修改`: `web/src/modules/assistant/AssistantHealthView.spec.ts`（新增解耦测试用例 a 与 b，覆盖率扩充至 10 项全过）
- `修改`: `docs/verification/task-31-frontend.md`（本验证报告）
