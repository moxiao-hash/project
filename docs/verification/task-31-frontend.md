# Task 31 验证证据：前端真实模型遥测与健康可观测性对齐（含真实零行响应诚实空状态）

- **执行 Agent**：ZCode (Gemini 3.8 Flash)
- **测试等级**：`[UNIT_TEST]` / `[STATIC_VALIDATION]`
- **执行时间**：2026-09-21 16:15:00 (Asia/Shanghai)
- **关联分支**：`agent/zcode-task-31-usage-health`
- **关联基础提交**：`59a3578` (origin/main)
- **交付状态**：**待 Codex 验收**（仅修改 `web/**` 与本验证文档，未碰触任何后端、Task 32 代码，未跟踪的 `docs/verification/task-29-codex-review.md` 保持原样未修改）

---

## 1. 契约对齐与诚实空状态重构

依据 Codex 明确的真实后端 Wire 响应契约（`AssistantHealthResponse` 与 `AssistantModelUsageStats`）以及针对无模型调用记录（零行数据）的诚实可观测性要求，前端完成了系统性重构与修复：

| 契约与审查要求 | 修复前偏差 | 修正与防护实现 |
| :--- | :--- | :--- |
| **1. 真实零行无数据状态** | 后端无数据返回 `modelCalls=0, models=[], priceStatus=NONE, usageEstimatedCost=0, currency=null, p50=0, p95=0` 时，前端错误渲染为 `0 USD`、`0 ms` 及 `按官方目录精确核算`，把未采集当成测量零 | 当 `priceStatus === 'NONE'` 或 `!health.modelCalls` 时，估算费用卡片和延迟分位卡片明确显示 `暂无数据`，副标题展示 `暂无已计价调用` 与 `暂无延迟采样数据`；绝不渲染 `0 USD`、`0 ms`，也不展示 `价格未知` 徽标（区分无样本与未知模型价格）。 |
| **2. 文案调整为估算核算** | 提示文案使用“精确核算”等绝对化词汇 | 统一替换为“按官方目录估算核算”与“基于官方目录估算的费用”，严格声明金额仅供估算参考。 |
| **3. 指标分区独立呈现** | 模型指标与执行指标混杂在同一卡片网格 | 彻底划分为两大独立区块：`[data-testid="model-telemetry-section"]`（真实模型调用与用量可观测性）与 `[data-testid="legacy-execution-section"]`（历史执行记录与治理统计）。 |
| **4. Token 不重复计数** | 前端自算并对 `modelTotalTokens` 与 `reasoningTokens` 二次相加 | 直接使用后端已准确聚合的 `health.modelTotalTokens` 及每行模型的 `m.totalTokens`，**绝不在前端再次叠加 `reasoningTokens`**，同时完整展示缓存命中、未缓存、输出与思考分项。 |
| **5. 动态货币与移除硬编码 ¥** | 界面硬编码包含 `¥` 人民币符号 | 彻底移除模板与样式中的每一个 `¥` / `￥` 符号，依据后端返回的 `currency` 动态展示（通常为 `USD`），如 `0.0425 USD`。 |
| **6. 未知价格显式表达** | 未知价格模型误显为 0 或 0.00 | 当且仅当 `priceStatus === 'UNKNOWN'` 或 `estimatedCost === null` 且有调用时，显式展示专属黄色未知徽标（`data-testid="cost-unknown-badge"` 提示“价格未知”），并展示 `含 N 次未计价调用`，绝不以 0 冒充零成本。 |
| **7. 真实维度与字段对齐** | 使用了自造字段 `modelId`、`callCount`、`isEstimated` | 对齐真实字段 `modelName`、`provider`、`calls`、`failedCalls`、`failureRate`、`cachedPromptTokens`、`uncachedPromptTokens`、`completionTokens`、`reasoningTokens`、`totalTokens`、`p50LatencyMs`、`p95LatencyMs`、`priceStatus`、`priceVersion`。 |
| **8. 移除虚构 Budget UI** | 页面包含了自造的 budget 进度条和超限横幅 | 彻底移除公开健康接口不存在的 `budget` 字段及全部相关组件（`budget-alert`、`budget-card`）。 |
| **9. 诚实空状态** | 无模型调用时缺少真实空状态 | 当 `models` 为空时，展示 `[data-testid="models-empty-state"]`（提示“暂无模型调用数据”）。 |

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

### 3.1 真实零行响应 RED 阶段失败证据
在编写针对实际零行响应 `modelCalls=0, models=[], priceStatus=NONE, usageEstimatedCost=0, currency=null, p50LatencyMs=0, p95LatencyMs=0` 的断言后执行测试，精确捕获到旧实现的失败日志：
```text
FAIL src/modules/assistant/AssistantHealthView.spec.ts > AssistantHealthView (Task 31 真实用量与模型遥测可观测性) > 真实零行响应：modelCalls=0, models=[], priceStatus=NONE, usageEstimatedCost=0, currency=null, p50LatencyMs=0, p95LatencyMs=0 显示暂无数据而非 0 USD/0 ms/价格未知
AssertionError: expected '模型调用与用量可观测性模型调用0暂无调用记录Token 用量0 缓存命中 …' not to contain '0 USD'

Expected: "0 USD"
Received: "模型调用与用量可观测性模型调用0暂无调用记录Token 用量0 缓存命中 0 · 未缓存 0 输出 0 · 思考 0估算费用0 USD按官方目录精确核算模型延迟分位0 ms P50 0 ms · P95 0 ms 模型维度明细按实际调用的模型底层 ID 与提供商独立统计 暂无模型调用数据"
```

### 3.2 GREEN 阶段成功证据
修复 `AssistantHealthView.vue` 后执行：`cd web && npm test -- src/modules/assistant/AssistantHealthView.spec.ts`
```text
 ✓ src/modules/assistant/AssistantHealthView.spec.ts (8 tests) 271ms

 Test Files  1 passed (1)
      Tests  8 passed (8)
```
用例清单（8 项全数通过）：
1. `模型遥测与执行记录分立展示：独立呈现模型可观测指标与执行层统计`
2. `Token 用量直接取后端 modelTotalTokens，绝不二次叠加 reasoningTokens`
3. `费用展示动态货币符号（通常为 USD），严禁出现硬编码人民币符号 ¥`
4. `当 priceStatus 为 UNKNOWN 或金额为 null 时，显式展示未知状态，绝不记为 0`
5. `无模型调用行时，真实呈现无数据空状态`
6. `分模型详细指标展示：覆盖失败次数、缓存/未缓存 Prompt、P50/P95 与定价版本`
7. `彻底移除虚构的 budget UI，公开健康接口不包含 budget 字段`
8. `真实零行响应：modelCalls=0, models=[], priceStatus=NONE, usageEstimatedCost=0, currency=null, p50LatencyMs=0, p95LatencyMs=0 显示暂无数据而非 0 USD/0 ms/价格未知`

### 3.3 全量前端测试套件回归
执行命令：`cd web && npm test -- --run`
```text
 Test Files  23 passed (23)
      Tests  149 passed (149)
   Duration  6.51s
```
全量 23 个测试文件、149 项测试 100% 全部通过，零功能回归。

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
✓ built in 2.74s
```
`vue-tsc` 类型检查零错误，生产打包 264 个模块全部成功。

### 3.5 代码格式与空白字符校验
执行命令：`git diff --check`
结果：无任何警告或输出，退出码为 0。

---

## 4. 变更文件清单

- `修改`: `web/src/types/assistant.ts`（使用 `AssistantModelUsageStats` 对齐真实后端契约，移除自造 budget 字段）
- `修改`: `web/src/modules/assistant/AssistantHealthView.vue`（彻底分立模型遥测与执行记录分区；区分 NONE/UNKNOWN/KNOWN；零行响应呈现诚实暂无数据；替换精确核算文案为估算核算；移除所有硬编码 ¥ 符号；直接使用 `modelTotalTokens`；渲染模型明细表与空状态）
- `修改`: `web/src/modules/assistant/AssistantHealthView.spec.ts`（补充真实零行响应 RED/GREEN 失败与成功测试，测试总数增至 8 项）
- `修改`: `docs/verification/task-31-frontend.md`（更新本验证报告）
