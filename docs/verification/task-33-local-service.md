# Task 33 验证证据：受控本地界面适配器服务 (TypeScript)

- **执行 Agent**：ZCode (Gemini 3.8 Flash)
- **测试等级**：`[UNIT_TEST]` / `[STATIC_VALIDATION]` / `[INTEGRATION_TEST]`
- **执行时间**：2026-09-22 16:40:00 (Asia/Shanghai)
- **关联分支**：`agent/zcode-task-33-local-adapters`
- **关联基础提交**：`9f8537115ac3d9c3bd86ddc1a43128db3a58414b` (Task 32 Codex 最终验收基线)
- **交付状态**：**待 Codex 验收**（全套受控 Unix Socket 服务端、HMAC-SHA256 长度前缀规范化验签、SQLite 原子持久化 Nonce 防重放、六大白名单动作与严格目标注册表、16 KiB 封帧与脱敏回执全部实现并通过严格 TDD 验证）

---

## 1. 规范要求与实现对照

| 契约条款 | 冻结契约要求 (task-33-frozen-contract.md) | ZCode 实现与安全边界细节 |
| :--- | :--- | :--- |
| **1. 传输与权限边界** | 仅监听 Unix Domain Socket，严禁开放 TCP/HTTP 端口；Socket 权限限制为当前所有者访问 | `LocalAutomationServer` 仅通过 `net.createServer` 绑定 UDS 路径；启动前创建 `0o700` 目录，监听后执行 `fs.chmodSync(socketPath, 0o600)`，并通过 `validateSocketMode` 校验 `(mode & 0o077) === 0`，若权限过宽即刻拒绝服务。 |
| **2. 封帧与输入防御** | 单行 UTF-8 JSON 封帧，上限 16 KiB；超限、非法 UTF-8、重复 JSON 键、未知字段、格式错误在副作用前拒绝 | `protocol.ts` 实现 `parseAndValidateRequestFrame`：① 检查 buffer 字节数 `<= 16384`；② `TextDecoder('utf-8', { fatal: true })` 严格校验编码；③ 专用状态机检测 JSON 重复键；④ 白名单校验仅允许 10 个固定字段，拦截 `url`、`selector`、`script`、`path` 等未知字段；⑤ 缺少必填字段即刻拒绝。 |
| **3. 签名与时效验证** | 独立 HMAC-SHA256 密钥（>=32 字节），覆盖固定 9 字段长度前缀规范化序列；默认 60s 时效，未来漂移 <=10s | `canonical.ts` 实现 `${byteLength}#${value}` 9 字段规范化（`version, requestId, ownerHash, channel, action, targetKey, issuedAt, expiresAt, nonce`）；`verifier.ts` 检查密钥字节长度 `>= 32`，时钟漂移 `<= 10s`，寿命 `<= 60s`，并使用 `crypto.timingSafeEqual` 防时序攻击。 |
| **4. SQLite 原子 Nonce 防重放** | SQLite 原子持久化消费与唯一约束；进程重启后仍拒绝重放；验签失败不得消耗合法 Nonce；并发竞争仅允许单一请求通过 | `nonceStore.ts` 基于 Node.js 内置 `DatabaseSync` (`node:sqlite`)，建立 `consumed_nonces` 主键表；Base64url 校验（>=128 bits，无 padding）；插入失败捕获唯一约束返回 `REPLAY_DETECTED`；验签或白名单失败分支不触发 `consume`；并发 Promise 仅一个成功；多实例重启复验通过。 |
| **5. 浏览器通道动作白名单** | `OPEN_STUDYPILOT_ROUTE`（仅 ASSISTANT, ASSISTANT_HEALTH, WORKSPACE_ARTIFACTS）；`FOCUS_AGENT_INPUT`（仅 ASSISTANT_INPUT）；`OPEN_RESULT_PANEL`（仅 WORKSPACE_RESULTS） | `actionRegistry.ts` 强校验基础 URL 必须为回环地址（`localhost` 或 `127.0.0.1`）；路由固定映射至 `/`、`/assistant/health`、`/workspaces`；定位器为源码内固定常量 `[data-testid="agent-message-input"]`；面板为固定常量 `workspace-results-panel`。 |
| **6. IDE 通道动作白名单** | `OPEN_REGISTERED_FILE`（不接受路径，通过不透明句柄映射；必须位于注册工作区根路径内常规文件，严拒符号链接）；`FOCUS_RUN_CONFIGURATION`；`SHOW_TEST_RESULT`（仅展示既有结果，不启动测试） | `actionRegistry.ts` 校验句柄必须在本地配置中；`fs.lstatSync` 强阻断符号链接；`fs.realpathSync` 解析实际路径并校验位于 `workspaceRoots` 内；`fs.statSync.isFile()` 校验必须为常规文件；`showTestResult` 仅做只读展示注册，无任何测试启动逻辑。 |
| **7. 极小狭窄适配器接口** | 不提供通用 click、type、press、open-path、script 或 shell；不使用 ProcessBuilder、AppleScript、通用 Playwright | `types.ts` 仅暴露 `BrowserAutomationAdapter` 与 `IdeaAutomationAdapter` 极窄方法；适配器仅执行目标状态核对，完全杜绝任意进程拉起或代码执行接口。 |
| **8. 脱敏回执契约** | 单行 JSON 封帧，`SUCCEEDED/FAILED/REJECTED`；`targetDigest` 为小写 sha256 hex；`message <= 200` 字符；严禁泄漏 URL、路径、凭据、DOM 或堆栈 | `protocol.ts` 实现 `sanitizeMessage`：自动脱敏 `[PATH]`、`[URL]`、`[REDACTED]` 密钥参数并剥离调用栈；截断至 200 字符以内；只有在适配器执行且目标状态验证成功时才返回 `SUCCEEDED`。 |

---

## 2. 运行环境与依赖

- **运行系统**：macOS darwin 25.5.0 arm64
- **Node.js**：v26.5.0
- **npm**：11.17.0
- **测试框架**：Vitest v5.0.1
- **类型检查**：TypeScript 5.8.2 (`tsc --noEmit` & `tsc`)
- **存储引擎**：Node.js 原生 `node:sqlite` (`DatabaseSync`)，无第三方原生 C++ 编译依赖，跨平台纯净稳定。

---

## 3. TDD RED / GREEN 闭环验证证据

### 3.1 RED 阶段失败证据汇总

在编写具体实现前，先创建测试套件并执行 `npm test` 确认失败：

1. **规范化载荷与目标摘要 (`tests/canonical.spec.ts`)**：
   ```text
   FAIL tests/canonical.spec.ts
   Error: Cannot find module '../src/canonical.js' imported from .../tests/canonical.spec.ts
   ```
2. **SQLite 原子 Nonce 持久化 (`tests/nonceStore.spec.ts`)**：
   ```text
   FAIL tests/nonceStore.spec.ts
   Error: Cannot find module '../src/nonceStore.js' imported from .../tests/nonceStore.spec.ts
   ```
3. **协议封帧与脱敏校验 (`tests/protocol.spec.ts`)**：
   ```text
   FAIL tests/protocol.spec.ts
   Error: Cannot find module '../src/protocol.js' imported from .../tests/protocol.spec.ts
   ```
4. **签名鉴权与时间窗口 (`tests/verifier.spec.ts`)**：
   ```text
   FAIL tests/verifier.spec.ts
   Error: Cannot find module '../src/verifier.js' imported from .../tests/verifier.spec.ts
   ```
5. **动作注册表与安全边界 (`tests/actionRegistry.spec.ts`)**：
   ```text
   FAIL tests/actionRegistry.spec.ts
   Error: Cannot find module '../src/actionRegistry.js' imported from .../tests/actionRegistry.spec.ts
   ```
6. **服务流水线与执行回执 (`tests/service.spec.ts`)**：
   ```text
   FAIL tests/service.spec.ts
   Error: Cannot find module '../src/service.js' imported from .../tests/service.spec.ts
   ```
7. **Unix Domain Socket 服务端 (`tests/server.spec.ts`)**：
   ```text
   FAIL tests/server.spec.ts
   Error: Cannot find module '../src/server.js' imported from .../tests/server.spec.ts
   ```

### 3.2 GREEN 阶段全量通过证据

执行命令：`cd local-automation-service && npm test`

```text
 RUN  v5.0.1 /Users/moxiao/IdeaProjects/project-zcode-task-33/local-automation-service

 ✓ tests/canonical.spec.ts (2 tests) 3ms
 ✓ tests/vectors.spec.ts (3 tests) 3ms
 ✓ tests/actionRegistry.spec.ts (6 tests) 8ms
 ✓ tests/protocol.spec.ts (9 tests) 7ms
 ✓ tests/verifier.spec.ts (7 tests) 5ms
 ✓ tests/nonceStore.spec.ts (4 tests) 11ms
 ✓ tests/service.spec.ts (4 tests) 13ms
 ✓ tests/server.spec.ts (4 tests) 14ms

 Test Files  8 passed (8)
      Tests  39 passed (39)
   Start at  08:38:27
   Duration  177ms
```

### 3.3 TypeScript 严格类型检查与构建

执行命令：`cd local-automation-service && npm run typecheck && npm run build`

```text
> local-automation-service@1.0.0 typecheck
> tsc --noEmit

> local-automation-service@1.0.0 build
> tsc
```
退出码为 0，零 warning，零 error。

---

## 4. 供 MiniMax Code Java 客户端对接的确定性测试向量

为保证 Java 端 `LocalAutomationClient` 与 TypeScript 服务端实现完全互通，本服务固化了不可变的确定性测试向量（见 `tests/vectors.spec.ts`）：

### 4.1 密钥与请求报文
```json
{
  "secretKey": "0123456789abcdef0123456789abcdef",
  "request": {
    "version": 1,
    "requestId": "c28d22db-363d-429a-8c85-618d3632cf4b",
    "ownerHash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    "channel": "PLAYWRIGHT_DOM",
    "action": "OPEN_STUDYPILOT_ROUTE",
    "targetKey": "ASSISTANT",
    "issuedAt": "2026-09-22T08:00:00Z",
    "expiresAt": "2026-09-22T08:01:00Z",
    "nonce": "dGVzdC1ub25jZS0xMjgtYml0cw"
  }
}
```

### 4.2 规范化载荷字符串 (UTF-8)
```text
1#136#c28d22db-363d-429a-8c85-618d3632cf4b64#e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b85514#PLAYWRIGHT_DOM21#OPEN_STUDYPILOT_ROUTE9#ASSISTANT20#2026-09-22T08:00:00Z20#2026-09-22T08:01:00Z26#dGVzdC1ub25jZS0xMjgtYml0cw
```

### 4.3 确定性哈希与签名期望值
- **HMAC-SHA256 签名**（小写 hex）：
  `e471c52c3d1ee78f52a0178f1ec2e0e09eac84a1354ce45141569336c7fdc943`
- **浏览器 targetDigest** (`PLAYWRIGHT_DOM/OPEN_STUDYPILOT_ROUTE/ASSISTANT`)：
  `d30b1c64274f91e571851b4d15914d7c8f7cb917c0689090be571279bbd9f0eb`
- **IDE targetDigest** (`IDEA_ACCESSIBILITY/OPEN_REGISTERED_FILE/FILE_SAMPLE`)：
  `362f3a4798eb4b7fc13e2f5a6f2ea3fe76f499bfa5f6cbba50d7a6e191986420`

---

## 5. 安全审计与已知边界

1. **所有权严格隔离**：
   - 本次改动仅限 `local-automation-service/**` 与 `docs/verification/task-33-local-service.md`。
   - 完全未触碰 `backend/**`、`web/**`、`ai-service/**` 或 `runner-service/**`。
   - 未合并 `main` 分支，未提前启动 Task 34。
2. **零泄漏审计**：
   - 密钥仅从运行时注入，不入库、不写日志；
   - 错误回执使用 `sanitizeMessage` 彻底脱敏截断至 200 字符以内；
   - 真实个人路径与未登记目标完全在服务边界前被拦截并返回统一安全错误码。
3. **真实性与阶段标记**：
   - 本阶段为 `[UNIT_TEST]` 与 `[INTEGRATION_TEST]` 级别；
   - 真实桌面浏览器/IDEA 的实际 GUI 联调留待 Task 34 全栈 REAL_E2E 验收，严禁在无证据时虚标。
