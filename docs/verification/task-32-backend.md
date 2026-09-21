# Task 32 验证证据：Developer Agent 加固与临时 Git 全链路

- **任务**：Task 32 Developer Agent 安全加固与临时 Git 全链路
- **交付状态**：**待 Codex 验收**（本文件不代表验收通过）
- **执行 Agent**：DeepSeek Harness 实现大部分后端/Runner 改动；**Mavis（MiniMax Code）接手完成**未完成部分并补齐证据
- **测试等级**：`[UNIT_TEST]` + `[INTEGRATION_TEST]`（H2/Spring 集成）+ `[REAL_E2E]`（真实 Java HTTP + 真实 MySQL 9.6/Flyway + 一次性临时 Git 仓库与本地 bare remote）
- **执行时间**：2026-09-21 21:27–22:0x (Asia/Shanghai)
- **分支**：`agent/deepseek-task-32-developer-hardening`
- **工作树**：`/Users/moxiao/IdeaProjects/project-deepseek-task-32`
- **接手基线**：`dac37e5`（含本 worktree 内约 22 个已修改文件 + 3 个新增文件的未提交工作）
- **首轮实现提交**：`5cc8bd1ad9653c46846f3f458ca50d4b67c7d6b4`（`fix: harden governed developer agent workflows`）
- **首轮证据提交**：`41a1fcde84ae1b5867875bb2ca4f994f2b17428a`（`docs: verify task 32 backend hardening`）
- **Codex 阻断项整改提交**：`176ccd38af3c493cd5f7c4f80ec55d51b46491d8`（`fix: bind git push to the effective push destination`）
- **本验证文档提交**：`docs: record task 32 push destination remediation`（本次提交）
- **真实容器链路**：**BLOCKED**（见 §5，未降级为宿主 shell 执行）

> 本文件已按 Codex 独立验收结论更新：首轮 501 项通过**不能**覆盖
> §3.6 的 push 目标绑定缺陷；该缺陷已按 TDD 整改并在本文件记录 RED/GREEN。

---

## 0. 范围、接手说明与文件所有权

本分支只改动 `backend/**`、`runner-service/**`、`ai-service/app/unified_agent/supervisor.py`
（Task 32 冻结文件清单内）、对应测试、`scripts/` 与 `docs/**`。
**未改动 `web/**`**（`git status` 中 `web/` 变更数为 0），未合并 `main`，未开发 Task 33。

接手时的实际状态与计划不同，需要先说明：

1. 工作树**无法编译**。`./mvnw -o -DskipTests test-compile` 报 3 个错误：
   `DeveloperWorkspaceHardeningTest:203`（`register` 未声明 `IOException`）与
   `WorkspaceDeveloperServiceTest:227,233`（`GitPushRequest` 已是 7 元 record，测试仍传 4 个参数）。
2. 存在 2 个失败测试，分别对应一处真实的契约回归与一处真实的安全缺口（见 §3.2、§3.3）。
3. 没有 Task 32 证据文档，远端也没有同名交付分支。

接手后按行为优先 TDD 完成上述缺口，并补齐 Task 32 Step 1/5/6 中缺失的攻击性测试与
一次性临时仓库 REAL_E2E。

| 文件 | 动作 | 归属 |
|---|---|---|
| `backend/.../agent/developer/**`（`WorkspaceDeveloperService`、`DeveloperTestRecommendation`、`GitPushPreview/Request`） | 修改 | 本分支 |
| `backend/.../agent/runner/**`（Governance/Security/SignedEnvelope/ExecutionEntity、Preview、Request、IsolatedRunnerExecutor） | 修改 | 本分支 |
| `backend/.../agent/tool/**`（Read/Write 工具 Schema、OutputSchemas） | 修改 | 本分支 |
| `backend/.../db/migration/V47__add_runner_working_directory.sql` | 新建（V46 已被 Task 31 占用） | 本分支 |
| `runner-service/**`（`entrypoint.sh`、`container_engine.py`、`protocol.py` 及测试） | 修改 | 本分支 |
| `ai-service/app/unified_agent/supervisor.py` | 修改（push 绑定字段原样回传） | 本分支 |
| `scripts/task32-temp-git-e2e.py` | 新建（Step 5 一次性仓库 REAL_E2E） | 本分支 |
| `web/**` | **未改动** | ZCode |

## 1. 运行环境

- **操作系统**：macOS darwin 25.5.0 arm64
- **Java**：OpenJDK 26.0.1（`backend/pom.xml` 编译目标 17）+ Maven Wrapper
- **Python**：3.12.13，复用主工作区解释器
  `/Users/moxiao/IdeaProjects/project/ai-service/.venv/bin/python`；本 worktree 内无独立 `.venv`
  （与 Task 31 证据文档记录的同一限制一致）。
- **MySQL**：本机 9.6.0，账号 `studypilot`，库 `studypilot`
- **容器运行时**：`docker` CLI 存在但 Colima 未运行；`podman` CLI 存在但 VM 从未启动（§5）
- **Node**：用于能力矩阵门禁脚本

## 2. 实现范围（以接手后的源码为准）

### 2.1 工作目录成为一等安全边界（Step 2）

- `RunnerExecutionRequest` / `RunnerExecutionPreview` / `RunnerSignedEnvelope` 增加
  `workingDirectory`；`IsolatedRunnerExecutor` 在执行前独立复核。
- 工作目录进入 HMAC 签名载荷（`RunnerSecurityService`），因此改动工作目录会使签名失效；
  同时进入**请求指纹**，同一幂等键换目录会被判定为不同请求。
- `confirm` 之前重新校验：目录缺失、被换成符号链接或移出工作区一律 409 失败关闭。
- `V47__add_runner_working_directory.sql` 新增 `runner_executions.working_directory`
  （`NOT NULL DEFAULT '.'`，历史行按工作区根目录回填）。
- Python 侧三处独立复核：`protocol.py::_validate_working_directory`（信封校验）、
  `canonical_payload` 绑定、`container_engine.py` 生成 `STUDYPILOT_WORKDIR`；
  `entrypoint.sh` 只接受 `/workspace` 之下并显式 `cd`，否则 `exit 64`。
- `DeveloperTestRecommendation.executions[]` 把 Maven/npm/pytest 绑定到已验证相对子目录；
  `projectDirectory()` 对符号链接子项目直接判定为"该项目不存在"。

### 2.2 读取与摘要加固（Step 3）

- `readFile`：超过 64 KiB **按大小直接拒绝**；其余内容施加 2000 行确定性上界并回报
  `truncated`；含 NUL 的二进制内容拒绝。
- `getFileTree` 的路径与文件名、`getGitStatus` 的改动/未跟踪路径、补丁失败信息统一经过
  `DeveloperOutputSanitizer`，避免文件名把凭据带进响应与审计。
- 已有的读取路径防线保持不变：绝对路径、`..` 穿越、逐段符号链接、工作区外真实路径、
  敏感文件与文本扩展名白名单全部在读取前拒绝。

### 2.3 commit / push 加固（Step 4）

- `GitPushPreview` / `GitPushRequest` 绑定 `remoteUrlDigest`（**JGit 实际 PUSH 使用的唯一目标地址**
  摘要，见 §2.4）、`expectedRemoteRef`、**`expectedRemoteRefCommit`**（预览时解析出的远端 ref 提交）
  与 `timeoutSeconds`；`validateGitPush` 逐项比对，任一漂移即失败关闭。
- `push` 调用 `setTimeout(...)`，并把超时限制在 1–120 秒。
- 确认 commit 仍然只创建本地提交，绝不隐式 push。

### 2.4 push 目标绑定修复（Codex 验收阻断项）

JGit 的 PUSH 走 `Transport.openAll(repository, remote, Operation.PUSH)`，其地址来自
`RemoteConfig.getPushURIs()`（`remote.<name>.pushurl`），**只有 pushurl 为空时才回退**
`getURIs()`（`remote.<name>.url`）。首轮实现只哈希 `remote.origin.url`，因此
`remote.origin.pushurl` 能把已确认的推送改到未绑定的目标；多个 pushurl 还可能出现部分写入，
而预览只绑定了一个无关地址。

修复（`WorkspaceDeveloperService`）：

- `effectivePushUris(config)`：用 `new RemoteConfig(config, "origin")` 派生唯一有效 PUSH URI
  （pushurl 非空用 pushurl，否则用 fetch url）；有效目标数量不为 1 时直接拒绝。
- `canonicalDestination(uri)`：只保留 scheme/host/port/path，**不含用户名与密码**——摘要无法反推
  凭据，也不会因凭据轮换误报漂移；写入目标只由这四项决定。
- `pushDestinationDigest(config)`：对规范化形式计算 SHA-256，继续复用公开字段 `remoteUrlDigest`，
  **未做 Schema 变更**。
- 确认阶段（`validateGitPush`）重新派生并比对；**无法再派生唯一目标时按冲突（409）**处理
  （预览是成功的，目标消失属于状态漂移，不是参数错误）。
- `pushConfirmed` 在取锁后立即再校验一次，随后才 `git.push()`；push 之后用
  `PushResult.getURI()` 复核 JGit **实际使用**的目标地址是否仍是绑定的那一个（静默重定向检测）。
- 分支、HEAD、远端 ref、超时、幂等、owner 隔离与 commit/push 独立确认行为不变。

## 3. RED / GREEN 证据

### 3.1 接手基线 RED（编译与既有测试）

```
./mvnw -o -DskipTests test-compile
[ERROR] .../DeveloperWorkspaceHardeningTest.java:[203,67] 未报告的异常错误 java.io.IOException
[ERROR] .../WorkspaceDeveloperServiceTest.java:[227,17] 无法将记录 GitPushRequest 中的构造器应用到给定类型

./mvnw -o test -Dtest='Developer*Test,Runner*Test,...'
[ERROR] DeveloperGitWorkflowTest.commitAndPushAreTwoIndependentHighRiskConfirmations:99 Status expected:<200> but was:<400>
[ERROR] DeveloperWorkspaceHardeningTest.pushConfirmationFailsClosedWhenTheRemoteRefAdvances:154 Expecting code to raise a throwable.
```

### 3.2 RED：远端 ref 漂移未被发现 → GREEN

`pushConfirmationFailsClosedWhenTheRemoteRefAdvances` 同时暴露两个缺陷：

1. 原测试的 `git update-ref refs/remotes/origin/main HEAD~1` 是**空操作**（该 ref 本来就是 `HEAD~1`），
   所以它并没有制造漂移；
2. 更根本的是，原来的 `expectedRemoteRef` 只绑定常量字符串 `refs/remotes/origin/main`，
   **永远不可能**发现远端前移。

因此本轮既修正测试（制造真实漂移：先多一个本地提交，再把远端跟踪 ref 前移到另一个提交，
并断言漂移后 `aheadCount` 仍为 1、只有 ref 提交发生变化），又新增
`expectedRemoteRefCommit` 真绑定。

RED（临时移除该字段的比对，`WorkspaceDeveloperService` 其余不变）：

```
[ERROR] DeveloperWorkspaceHardeningTest.pushConfirmationFailsClosedWhenTheRemoteRefAdvances
        Expecting code to raise a throwable.
[ERROR] Tests run: 1, Failures: 1, Errors: 0
```

GREEN（恢复比对后）：

```
[INFO] Tests run: 1, Failures: 0, Errors: 0
```

### 3.3 RED：既有 push 契约测试回归 → GREEN

`developer.git.push` 收紧为"必须回传预览绑定的全部事实"后，既有
`DeveloperGitWorkflowTest`（只传 4 个字段）得到 400。这是**冻结计划 Step 4 要求**的契约变更，
因此按新契约更新测试与 `supervisor.py`（见 §6），而不是放宽校验。

### 3.4 RED：三处读取攻击测试 → GREEN

一次性移除 4 处读取防线（大小上界、NUL 二进制、`..` 穿越、工作区包含性）：

```
[ERROR] DeveloperWorkspaceHardeningTest.readFileRejectsBinaryContent:145
[ERROR] DeveloperWorkspaceHardeningTest.readFileRejectsFilesBeyondTheDeterministicSizeBound:157
[ERROR] DeveloperWorkspaceHardeningTest.readFileRejectsHostileAndEscapingPathsBeforeAnyContentIsReturned:132
[ERROR] Tests run: 3, Failures: 3
```

恢复后 `diff` 确认源文件与实验前**字节一致**，并：

```
[INFO] Tests run: 12, Failures: 0, Errors: 0  -- DeveloperWorkspaceHardeningTest
```

### 3.5 RED：重复确认幂等 → GREEN（纵深防御）

临时削弱 `AgentToolActionService.claimConfirmation` 对终态的短路（让 SUCCEEDED 动作重新执行）：

```
[ERROR] DeveloperGitWorkflowTest.prepareIsSideEffectFreeAndRepeatedConfirmationNeverCommitsOrPushesAgain:153
        JSON path "$.status" expected:<SUCCEEDED> but was:<FAILED>
```

值得记录的行为：即使动作层幂等被移除，重复确认也**没有**产生第二次提交——服务层 HEAD/指纹
绑定拒绝了它并把该动作收敛为 FAILED，即"重复确认不新增提交或推送"由两层独立保证。
恢复后：

```
[INFO] Tests run: 2, Failures: 0, Errors: 0  -- DeveloperGitWorkflowTest
```

### 3.6 RED：push 目标绑定缺陷（Codex 验收阻断项）→ GREEN

Codex 独立验收指出：`GitPushPreview.remoteUrlDigest` 只哈希 `remote.origin.url`，
但 JGit 的 PUSH 使用 `RemoteConfig.getPushURIs()`（`remote.origin.pushurl`），
仅在 pushurl 为空时才回退 fetch url。因此 pushurl 可以把已确认的推送改到未绑定的目标，
多个 pushurl 还可能部分写入。新增三个测试，修复前**全红**：

```
[ERROR] DeveloperWorkspaceHardeningTest.pushPreviewBindsTheEffectivePushUrlInsteadOfTheFetchUrl:222
        Expecting actual:
          "60c63bba1e1efbd9a97df0830fe20c3e03f5b3f85e2eec23551b3f4391bd7fd6"
        not to be equal to:
          "60c63bba1e1efbd9a97df0830fe20c3e03f5b3f85e2eec23551b3f4391bd7fd6"
[ERROR] DeveloperWorkspaceHardeningTest.pushConfirmationFailsClosedWhenThePushUrlChangesAfterPreview:242
        Expecting code to raise a throwable.
[ERROR] DeveloperWorkspaceHardeningTest.pushRejectsMultipleEffectiveDestinationsInsteadOfPartiallyPushing:261
        Expecting code to raise a throwable.
[ERROR] Tests run: 15, Failures: 3, Errors: 0, Skipped: 0
```

第一条失败的对比值**完全相同**，正是缺陷本身：设置 `pushurl` 前后摘要一字不变，
说明摘要根本没有绑定实际写入目标。三个测试分别覆盖：

1. `origin.url` 指向 bare A、`origin.pushurl` 指向 bare B：预览必须绑定 B，
   确认后只有 B 前进、A 不变（修复后 `pushConfirmed` 断言 `bareHead(B) == expectedHead`）。
2. 预览后把 `pushurl` 改到另一个 bare：确认必须 `ConflictException`，两个远端都不得变化。
3. 两个 pushurl（以及无 pushurl 但多个 fetch url）：预览与确认都必须拒绝，不得部分推送。

修复后：

```
[INFO] Tests run: 15, Failures: 0, Errors: 0  -- DeveloperWorkspaceHardeningTest
```

其中第 3 项在实现过程中还暴露一处语义不一致并已修正：确认阶段"无法再派生唯一目标"
原先抛出 400 语义的 `IllegalArgumentException`，但预览此前是成功的，目标消失属于
**状态漂移**，因此改为 409 语义的 `ConflictException`（与 `validateGitPush` 的契约一致）。

## 4. 自动化验证结果

整改后（`176ccd3`）的新鲜结果：

| 检查 | 命令 | 结果 |
|---|---|---|
| Java 聚焦（Task 32 相关 9 个测试类） | `./mvnw -o test -Dtest='DeveloperWorkspaceHardeningTest,WorkspaceDeveloperServiceTest,DeveloperPatchWorkflowTest,DeveloperGitWorkflowTest,RunnerGovernanceWorkflowTest,RunnerProtocolSecurityTest,RunnerWorkingDirectoryWorkflowTest,UnixSocketRunnerClientTest,AgentToolCoverageTest'` | **70 项通过** |
| Java 全量 | `./mvnw -o test` | **504 项通过，0 失败 0 错误** |
| ai-service 全量 | `PYTHONPATH=ai-service <venv>/python -m pytest -q ai-service/tests` | **556 项通过**（1 条上游 Starlette 弃用警告） |
| runner-service | `PYTHONPATH=runner-service <venv>/python -m pytest -q runner-service/tests` | **35 项通过** |
| Ruff | `ruff check runner-service` / `ruff check ai-service`（根目录执行） | 全部通过 |
| 能力矩阵门禁 | `node scripts/verify-agent-capability-matrix.mjs` | 通过（31 页面 / 64 工具） |
| 空白检查 | `git diff --check` | 干净 |

> 首轮（`5cc8bd1`）结果是 Java 501 / 聚焦 69；整改新增 3 个 push 目标绑定测试后为 504 / 70。
> 上述均为自动化/H2 证据，除 §5 的 REAL_E2E 外不得写成真实全链路通过。

## 5. Step 5：一次性临时仓库 REAL_E2E（`[REAL_E2E]`，部分 BLOCKED）

脚本：`scripts/task32-temp-git-e2e.py`。它新建一次性临时工作仓库与本地 bare remote
（**不使用 StudyPilot 主仓库作破坏性样本**），通过真实运行中的 Java 服务 + 真实 MySQL
驱动 internal agent-tools + 专用确认链路，并在结束时删除整个临时目录。

**整改后复跑**（`176ccd3`，改动 push 目标绑定之后）：链路结果与下表一致，`push-confirm` 仍为 PASS
（真实 `file://` bare remote 的远端 ref 等于本地 HEAD），说明新的目标派生与 push 后复核
没有破坏真实推送；容器段仍为 BLOCKED。

运行方式（本次实际执行）：

```
# 用主工作区既有 .env 注入本地开发密钥（不打印、不落盘），INTERNAL_SERVICE_TOKEN 用本次运行专属随机值
cd backend && set -a && . /Users/moxiao/IdeaProjects/project/ai-service/.env && set +a \
  && INTERNAL_SERVICE_TOKEN=<本次运行随机令牌> SPRING_PROFILES_ACTIVE=local ./mvnw -o spring-boot:run
INTERNAL_SERVICE_TOKEN=<同一令牌> <venv>/python scripts/task32-temp-git-e2e.py
```

真实 MySQL/Flyway 证据（真实运行，不是 H2）：

```
flyway_schema_history: 47 | add runner working directory | success=1
show columns from runner_executions like 'working_directory'
  -> working_directory  varchar(512)  NO  ''
```

脚本输出（原文）：

```json
{
  "result": "BLOCKED",
  "scope": "real Java HTTP + real MySQL + real disposable git repo/bare remote",
  "blocked": ["container-runner: actionStatus=FAILED detail=必须配置独立且不少于 32 字节的 Runner 签名密钥"],
  "not_covered": ["真实容器链路（无容器运行时，未降级为宿主 shell）",
                  "真实模型调用与 Python 超级visor（不属于 Task 32 范围）"],
  "steps": [
    {"step": "disposable-repo", "outcome": "PASS", "detail": "已创建一次性工作仓库与本地 bare remote（未触碰主仓库）"},
    {"step": "register-workspace", "outcome": "PASS", "detail": "独立用户 + 一次性工作区登记 + 管理授权"},
    {"step": "patch-preview-confirm", "outcome": "PASS", "detail": "补丁预览只读，确认后文件已变更且未产生提交"},
    {"step": "diff-and-test-recommendation", "outcome": "PASS", "detail": "Git diff 真实反映改动；测试推荐绑定子目录 [('MAVEN_TEST', 'backend')]"},
    {"step": "container-runtime-probe", "outcome": "BLOCKED", "detail": "docker: failed to connect to the docker API at unix:///Users/moxiao/.colima/default/docker.sock; podman: Cannot connect to Podman. ... connection refused"},
    {"step": "container-test", "outcome": "BLOCKED", "detail": "无可用容器运行时，runner 治理动作收敛为 FAILED，未降级为宿主 shell"},
    {"step": "commit-confirm", "outcome": "PASS", "detail": "确认 commit 产生本地提交，远端保持不变"},
    {"step": "push-confirm", "outcome": "PASS", "detail": "push 确认后远端 ref 等于本地 HEAD"},
    {"step": "governance-evidence", "outcome": "PASS", "detail": "SUCCEEDED 执行类型 ['CODE_PATCH_APPLICATION', 'GIT_COMMIT', 'GIT_PUSH']；审计与通知在复查前后一致"}
  ]
}
```

### 容器链路为何是 BLOCKED（独立复验，不是沿用旧结论）

```
$ docker ps
failed to connect to the docker API at unix:///Users/moxiao/.colima/default/docker.sock ... no such file or directory
$ colima status
colima is not running
$ podman run --rm docker.io/library/alpine:3 echo ok
Cannot connect to Podman ... dial tcp 127.0.0.1:56194: connect: connection refused
$ podman machine list
podman-machine-default*  libkrun  2 weeks ago  LAST UP: Never
```

`docker`/`podman` 二进制存在但**没有运行中的隔离容器运行时**，因此：
容器测试步骤记为 BLOCKED，**没有降级为宿主 shell 执行**，也没有启动任何容器 VM 去"凑"通过。
此外该环境的 Runner 签名密钥未配置，runner 治理动作在签名阶段即失败关闭
（`必须配置独立且不少于 32 字节的 Runner 签名密钥`）。

## 6. 契约变更（需 Codex 知情/冻结确认）

以下都是冻结计划 Step 1–4 明确要求的结果，但会破坏旧调用方，必须在验收时确认：

1. **`developer.git.push` 参数收紧**：新增必填 `remoteUrlDigest`、`expectedRemoteRef`、
   `expectedRemoteRefCommit`、`timeoutSeconds`；`GitPushPreview`/`GitPushRequest` 同步。
   已同步：工具输入 Schema、`AgentToolOutputSchemas.PUSH_PREVIEW`、
   `ai-service/app/unified_agent/supervisor.py`（原样回传预览事实）与全部相关测试。
   注意：仅绑定 `expectedRemoteRef`（ref 名称）是常量，无法发现远端前移，
   `expectedRemoteRefCommit` 才是真正的漂移检测。
   整改（§3.6）**只改变 `remoteUrlDigest` 的语义**（由 fetch URL 改为有效 PUSH 目标地址的摘要），
   **字段名、类型与 Schema 均未变化**，因此调用方无需改动。
   新增策略：**有效推送目标必须唯一**——配置了多个 `pushurl`，或在没有 `pushurl` 时配置了多个
   `url`，预览与确认都会被拒绝（预览 400 / 确认 409），不允许部分多目标推送。
2. **`workingDirectory`**：`RunnerExecutionRequest`/`Preview`/`SignedEnvelope` 新增字段，
   DB 迁移 V47；`runner.execution.preview`、`runner.check.run`、
   `runner.dependencies.prepare` 三个工具新增**可选** `workingDirectory` 参数。
   HMAC 载荷与 canonical payload 都随之变化：`RunnerProtocolSecurityTest` 中的
   Python golden 签名已按新载荷更新。
3. **`DeveloperTestRecommendation` 新增 `executions[]`**；`templates` 保留以兼容既有调用方。

## 7. 未覆盖、已知限制与需 Codex 决策的观察

1. **真实容器链路未执行**（BLOCKED，§5）。Step 7 要求的"真实容器链路"无法在本机完成。
2. **真实模型调用与 Python Supervisor 全链路**不属于 Task 32 范围，本次未运行；
   `supervisor.py` 的改动只经过 211 个 unified_agent 单测与静态检查。
3. **观察到但与 Task 32 无关的既有缺陷（未修复，供 Codex 决策）**：
   `POST /api/agent-grants` 传入远期 `expiresAt`（如 `2099-01-01`）返回 **500**：
   `agent_grants.expires_at` 是 MySQL `TIMESTAMP`（上限 2038），报
   `Data truncation: Incorrect datetime value`。H2 测试不会暴露该问题；
   `scripts/task32-temp-git-e2e.py` 因此改用 2037。
4. **`readFile` 对 >64 KiB 文件整体拒绝**（并非截断）：这是"确定性 size 上界"的实现方式，
   但意味着 Developer Agent 无法读取大文件。若产品希望改为截断+`truncated=true`，
   属于行为变更，需 Codex 决策。
5. `V47` 依赖 V46 之后为空号；若 Codex 在其他分支已占用 V47，需要重新编号（本分支未合并 `main`）。
6. **push 目标校验的残余时间窗口（已知限制，未隐藏）**：`pushConfirmed` 在取工作区锁后立即重新
   派生并比对目标摘要，随后才调用 `git.push()`；两次调用之间仍存在同机进程改写
   `.git/config` 的极短窗口。本分支用两种方式收敛该风险：(a) 比对紧贴 push，(b) push 之后用
   `PushResult.getURI()` 复核实际目标并在不一致时显式失败。**未**改为"按 URI 直接推送"，
   因为那会改变真实远端的认证路径（凭据助手/URL 重写），而本环境无法验证该行为；
   如需彻底消除窗口，建议作为独立任务在具备真实远端的环境中评估。
7. 推送失败时的异常按既有约定包装为 `IllegalArgumentException("执行 Git push 失败", cause)`；
   `GlobalExceptionHandler` 与 `AgentToolActionService.safeError` 都只取
   `exception.getMessage()`（不含 cause、不含栈），且新增消息均为静态文本、
   `canonicalDestination` 明确剔除用户名与密码，因此不会把带凭据的 URL 写入响应、通知或审计。

## 8. 下一步（交给 Codex）

1. 独立复跑 §4 的命令（注意 worktree 内无 `.venv`，需用主工作区解释器）。
2. 复审 §3.6 的 push 目标绑定修复：`effectivePushUris` 的派生是否与 JGit
   `Transport.openAll(..., Operation.PUSH)` 语义一致、多目标策略是否为期望的"唯一目标"策略，
   以及 §7.6 记录的残余窗口与替代方案取舍是否可接受。
3. 审查 §6 的其他契约变更是否与冻结契约一致，特别是 `developer.git.push` 的必填字段
   是否已由前端/其他调用方按新契约传参（本分支未改 `web/**`；Codex 已确认 Python
   supervisor 是当前 push 调用方且已同步）。
4. §7.3/§7.4 两项观察按 Codex 结论保持现状：`/api/agent-grants` 的 2038 问题记入独立 backlog，
   `readFile` >64 KiB 维持确定性拒绝。
5. 在本机具备容器运行时后再补 Step 7 的真实容器链路，Task 32 的容器部分在此之前保持 BLOCKED。
