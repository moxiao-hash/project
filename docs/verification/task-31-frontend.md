# Task 31 验证证据：前端真实模型遥测与健康可观测性对齐

- **执行 Agent**：ZCode (Gemini 3.8 Flash)
- **测试等级**：`[UNIT_TEST]` / `[STATIC_VALIDATION]`
- **执行时间**：2026-09-21 16:00:00 (Asia/Shanghai)
- **关联分支**：`agent/zcode-task-31-usage-health`
- **关联基础提交**：`59a3578` (origin/main)
- **交付状态**：**待 Codex 验收**（仅修改 `web/**` 与本验证文档，未碰触任何后端、Task 32 代码，未跟踪的 `docs/verification/task-29-codex-review.md` 保持原样未修改）

---

## 1. 契约对齐与设计重构

依据 Codex 明确的真实后端 Wire 响应契约（`AssistantHealthResponse` 与 `AssistantModelUsageStats`），前端彻底重构并消除了此前自造字段与偏差：

| 契约要求 | 修复前偏差 | 修正与防护实现 |
| :--- | :--- | :--- |
| **1. 指标分区独立呈现** | 模型指标与执行指标混杂在同一卡片网格 | 彻底划分为两大独立区块：`[data-testid="model-telemetry-section"]`（真实模型调用与用量可观测性）与 `[data-testid="legacy-execution-section"]`（历史执行记录与治理统计）。 |
| **2. Token 不重复计数** | 前端自算并对 `modelTotalTokens` 与 `reasoningTokens` 二次相加 | 直接使用后端已准确聚合的 `health.modelTotalTokens` 及每行模型的 `m.totalTokens`，**绝不在前端再次叠加 `reasoningTokens`**，同时完整展示缓存命中、未缓存、输出与思考分项。 |
| **3. 动态货币与移除硬编码 ¥** | 界面硬编码包含 `¥` 人民币符号 | 彻底移除模板与样式中的每一个 `¥` / `￥` 符号，依据后端返回的 `currency` 动态展示（通常为 `USD`），如 `0.0425 USD`。 |
| **4. 未知价格显式表达** | 未知价格模型误显为 0 或 0.00 | 当 `priceStatus === 'UNKNOWN'` 或 `estimatedCost === null` 时，显式展示专属黄色未知徽标（`data-testid="cost-unknown-badge"` 提示“价格未知”），并展示 `含 N 次未计价调用`，绝不以 0 冒充零成本。 |
| **5. 真实维度与字段对齐** | 使用了自造字段 `modelId`、`callCount`、`isEstimated` | 对齐真实字段 `modelName`、`provider`、`calls`、`failedCalls`、`failureRate`、`cachedPromptTokens`、`uncachedPromptTokens`、`completionTokens`、`reasoningTokens`、`totalTokens`、`p50LatencyMs`、`p95LatencyMs`、`priceStatus`、`priceVersion`。 |
| **6. 移除虚构 Budget UI** | 页面包含了自造的 budget 进度条和超限横幅 | 彻底移除公开健康接口不存在的 `budget` 字段及全部相关组件（`budget-alert`、`budget-card`）。 |
| **7. 诚实空状态** | 无模型调用时缺少真实空状态 | 当 `models` 为空时，展示 `[data-testid="models-empty-state"]`（提示“暂无模型调用数据”）。 |

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
在更新类型定义与测试用例后，针对旧版 `AssistantHealthView.vue` 运行测试，精确捕获到 6 项因未对齐真实契约导致的断言失败：
```text
FAIL src/modules/assistant/AssistantHealthView.spec.ts (7 tests | 6 failed)
× 模型遥测与执行记录分立展示：独立呈现模型可观测指标与执行层统计
  → expected false to be true (缺少 data-testid="model-telemetry-section")
× Token 用量直接取后端 modelTotalTokens，绝不二次叠加 reasoningTokens
  → Cannot call text on an empty DOMWrapper
× 费用展示动态货币符号（通常为 USD），严禁出现硬编码人民币符号 ¥
  → expected text to contain '0.0425' and 'USD'
× 当 priceStatus 为 UNKNOWN 或金额为 null 时，显式展示未知状态，绝不记为 0
  → expected false to be true (缺少 data-testid="cost-unknown-badge")
× 无模型调用行时，真实呈现无数据空状态
  → expected false to be true (缺少 data-testid="models-empty-state")
× 分模型详细指标展示：覆盖失败次数、缓存/未缓存 Prompt、P50/P95 与定价版本
  → expected false to be true (缺少 data-testid="models-table")
✓ 彻底移除虚构的 budget UI，公开健康接口不包含 budget 字段
```

### 3.2 GREEN 阶段成功证据
执行命令：`cd web && npm test -- src/modules/assistant/AssistantHealthView.spec.ts`
```text
✓ src/modules/assistant/AssistantHealthView.spec.ts (7 tests) 135ms

Test Files  1 passed (1)
     Tests  7 passed (7)
```
测试用例全部针对真实契约建立：
1. `模型遥测与执行记录分立展示：独立呈现模型可观测指标与执行层统计`
2. `Token 用量直接取后端 modelTotalTokens，绝不二次叠加 reasoningTokens`
3. `费用展示动态货币符号（通常为 USD），严禁出现硬编码人民币符号 ¥`
4. `当 priceStatus 为 UNKNOWN 或金额为 null 时，显式展示未知状态，绝不记为 0`
5. `无模型调用行时，真实呈现无数据空状态`
6. `分模型详细指标展示：覆盖失败次数、缓存/未缓存 Prompt、P50/P95 与定价版本`
7. `彻底移除虚构的 budget UI，公开健康接口不包含 budget 字段`

### 3.3 全量前端测试套件回归
执行命令：`cd web && npm test -- --run`
```text
Test Files  23 passed (23)
     Tests  148 passed (148)
  Duration  6.47s
```
全量 23 个测试文件、148 项测试 100% 全部通过，零功能回归。

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
✓ built in 2.65s
```
`vue-tsc` 类型检查零错误，生产打包 264 个模块全部成功。

### 3.5 代码格式与空白字符校验
执行命令：`git diff --check`
结果：无任何警告或输出，退出码为 0。

---

## 4. 变更文件清单

- `修改`: `web/src/types/assistant.ts`（以 `AssistantModelUsageStats` 替换旧接口，对齐 `AssistantHealth` 完整真实字段，移除 budget 接口）
- `修改`: `web/src/modules/assistant/AssistantHealthView.vue`（彻底重构为模型遥测与执行记录两大独立分区、移除所有硬编码 ¥ 符号、直接使用 `modelTotalTokens`、实现价格未知显式徽标、构建分模型明细表与诚实空状态）
- `修改`: `web/src/modules/assistant/AssistantHealthView.spec.ts`（重构 7 项单元测试，精确覆盖真实 Wire 响应契约、动态 USD、未知价格、非重复计数与分区展示）
- `修改`: `docs/verification/task-31-frontend.md`（本验证报告）
