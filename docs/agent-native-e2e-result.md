# StudyPilot Agent 原生应用全链路验收结果 (Task 26)

> 执行日期：2026-09-07  
> 责任主体：Gemini / Codex 协同基线  
> 环境：macOS darwin arm64，MySQL 9.6 (Port 3306)，Spring Boot (Port 8080)，FastAPI (Port 8000)，Vue (Port 5173)，Local Runner (Unix Socket + Docker 容器)

---

## 1. 真实全链路验收总览

本次验收基于 Task 12～25 建立的 Agent 原生治理底座，通过 Java 公共门面 `/api/**`，对真实 MySQL、Spring Boot、FastAPI、Vue、Local Runner 与容器化沙箱进行了完整的端到端全链路闭环验证。所有请求遵循四层信任边界，前端仅传递 Bearer Token，服务端杜绝客户端伪造身份。

| 验收项 | 覆盖模块与接口 | 验证结果 | 安全与治理边界判定 |
|---|---|---|---|
| **服务与运行健康探针** | `GET /actuator/health`<br>`GET /health`<br>`GET /api/assistant/health` | **UP / PASS** | 个人健康指标按登录用户隔离，未发生越权数据渗透。 |
| **认证与身份注入防护** | `POST /api/auth/register`<br>`POST /api/assistant/conversations` | **PASS** | 客户端在 JSON 中伪造 `ownerId: "attacker"` 时，Java Facade 强行覆盖为真实 Token 用户 ID。 |
| **AI 凭据脱敏安全** | `GET /api/ai-settings` | **PASS** | 响应中绝不返回明文 API Key，仅返回配置状态与掩码，AES-GCM 主密钥解密受限。 |
| **学习上下文与动态路线** | `GET /api/roadmaps/current`<br>`GET /internal/confirmed-learning-plans` | **PASS** | 统一 Agent 启动每轮前重新加载服务端最新节点进度与未完成任务。 |
| **受治理动作卡与写操作治理** | `POST /api/assistant/conversations/{id}/messages` | **PASS** | 高风险动作返回 `WAITING_CONFIRMATION` 与白名单 UI Action，未确认前业务数据保持 `TODO`。 |
| **专用确认与幂等保证** | `POST /api/assistant/conversations/{id}/actions/{id}/confirm` | **PASS** | 普通聊天文本无法触发写入，仅专用确认卡接口生效；重复确认幂等防护生效。 |
| **SSE 流式事件与断线续传** | `GET /api/assistant/conversations/{id}/events` | **PASS** | 事件携带全局递增 `sequence`，支持 `Last-Event-ID` 续传游标重放未消费事件。 |
| **Local Runner 容器执行预览** | `POST /api/runner/preview` | **PASS** | 白名单命令模板（`MAVEN_TEST` 等）无副作用预览，严格绑定登记工作区与安全路径。 |
| **Runner 断网容器隔离与验签** | `UnixSocketRunnerClient`<br>`studypilot/runner-*` 镜像 | **PASS** | HMAC-SHA256 签名信封防篡改、防重放，容器使用 `--network none`、只读根与 tmpfs 隔离。 |
| **成果物 70 分 Rubric 评审** | `POST /api/roadmap-nodes/{id}/artifact-reviews` | **PASS** | 必须由归属相同且成功的 Runner 真实测试记录背书，AI 结构化评分未达标严禁推进节点。 |
| **Developer Agent Git 独立控制** | `developer.git.commit`<br>`developer.git.push` | **PASS** | Commit 与 Push 分离为独立高风险动作，独立预览、独立确认，防越权与目录逃逸。 |

---

## 2. 自动化测试套件全量回归记录

在本地真实环境（含运行中的 MySQL 9.6 与 Colima/Docker 容器环境）执行全量回归，各端通过情况如下：

```text
================================================================================
服务模块                  执行命令                                  结果状态
================================================================================
Java 后端 (Spring Boot)   cd backend && mvn test -q                 362 passed (新增 1 个全链路 E2E 测试)
AI 服务 (FastAPI)         ai-service/.venv/bin/pytest -q ai-service 302 passed, 1 warning (Starlette 兼容)
AI 代码格式校验           ai-service/.venv/bin/ruff check ai-service All checks passed!
Runner 服务 (隔离容器)    pytest -q runner-service/tests            21 passed
Runner 代码格式校验       ruff check runner-service                 All checks passed! (已格式化导入块)
前端 (Vue 3 + TypeScript) cd web && npm test -- --run               124 passed (22 test files)
前端类型与生产构建        cd web && npm run build                   vue-tsc passed, vite build passed
代码规范与空白检查        git diff --check                          Clean (无格式悬挂)
================================================================================
```

---

## 3. 架构约束与已知运行限制

1. **单机单进程约束**：
   - 加密 SQLite 会话存储与本地 Qdrant 向量索引均为单进程文件锁绑定，FastAPI 不得以多 worker（如 `--workers 2`）或多实例运行。
2. **Local Runner 本机通信**：
   - 依赖宿主机 Unix Socket `/tmp/studypilot-runner/runner.sock`，要求 Spring Boot 与 Runner 共享相同的 32+ 字节签名密钥 `STUDYPILOT_RUNNER_SIGNING_SECRET`。
   - 依赖已构建的 Docker 镜像：`studypilot/runner-maven:1`、`studypilot/runner-node:1`、`studypilot/runner-python:1`。
3. **真实模型与网络降级**：
   - 当 DeepSeek 或 Tavily 网络抖动或配额耗尽时，系统降级为本地缓存与传统操作，业务核心事实不受外部服务宕机影响。
