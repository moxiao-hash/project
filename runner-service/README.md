# StudyPilot Local Runner

Local Runner 是独立于 Spring Boot 和 FastAPI 的本机执行服务。它只监听 Unix Socket，
校验 Java 签发的 HMAC 信封后，使用 Docker 或 Podman 的固定模板运行测试；容器引擎或
Runner 不可用时直接失败，绝不退回宿主机执行命令。

## 安全边界

- 只接受 `MAVEN_TEST`、`MAVEN_COMPILE`、`NPM_TEST`、`PYTEST` 和
  `PREPARE_DEPENDENCIES` 固定命令。
- 测试与编译容器默认断网；准备 Maven 依赖是需要逐次确认的联网高风险操作。
- 登记工作区以只读方式挂载到 `/source`，入口脚本将其复制到容器 tmpfs `/workspace`，
  因此构建产物不会写回用户源码目录。
- 容器使用只读根文件系统、非 root 用户、移除 capabilities、进程/CPU/内存/超时限制，
  且不继承宿主 `.env`、SSH 或 API Key。
- nonce 保存在 SQLite，Runner 重启后仍会拒绝重放；Socket 和目录权限分别为 `0600`
  与 `0700`。

## 准备与启动

本机须先安装并启动 Docker Desktop 或 Podman。然后在仓库根目录构建三个固定镜像：

```bash
docker build -f runner-service/images/maven/Dockerfile -t studypilot/runner-maven:1 runner-service
docker build -f runner-service/images/node/Dockerfile -t studypilot/runner-node:1 runner-service
docker build -f runner-service/images/python/Dockerfile -t studypilot/runner-python:1 runner-service
```

生成一次签名密钥，并在启动 Runner 与 Spring Boot 的终端中使用同一个值：

```bash
export STUDYPILOT_RUNNER_SIGNING_SECRET="$(openssl rand -hex 32)"
export STUDYPILOT_RUNNER_ALLOWED_ROOTS="/Users/你的用户名/IdeaProjects"
export STUDYPILOT_RUNNER_SOCKET_PATH="/tmp/studypilot-runner/runner.sock"
export STUDYPILOT_RUNNER_NONCE_DB="/tmp/studypilot-runner/nonces.sqlite3"
# 可选；默认 ~/.cache/studypilot-runner/staging。macOS 容器虚拟机必须能共享该目录。
export STUDYPILOT_RUNNER_STAGING_ROOT="$HOME/.cache/studypilot-runner/staging"

PYTHONPATH=runner-service ai-service/.venv/bin/python -m studypilot_runner
```

Spring Boot 使用 `STUDYPILOT_RUNNER_SIGNING_SECRET` 和
`STUDYPILOT_RUNNER_SOCKET_PATH` 自动映射对应配置。密钥不能提交到 Git；未配置独立的
至少 32 字节密钥时，Java 会拒绝签发执行信封。

当前本地开发通常由同一 macOS 用户运行 Java 与 Runner。正式部署若要求 FastAPI 在
操作系统权限层无法访问 Socket，应让 FastAPI 使用不同的系统账户运行，并保持 Runner
Socket 为所有者专用。

## 测试

协议、重放、路径、Socket 和容器命令策略测试不要求本机安装 Docker：

```bash
PYTHONPATH=runner-service ai-service/.venv/bin/python -m pytest -q runner-service/tests
ai-service/.venv/bin/ruff check runner-service
```

2026-09-06 已在 Colima + Docker 上完成真实验收：pytest、npm test 直接在断网容器内
通过；Maven 先用已确认的 `dependency:go-offline` 联网准备缓存，再在断网容器内完成
`mvn test`。更换操作系统或容器引擎后仍应重新执行这组验收。
