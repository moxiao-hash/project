# StudyPilot

StudyPilot 是 moxiao 个人使用的 Java + AI 交互式学习平台，不是面向通用学科、
教师端或多人课程市场的泛学习平台。登录后的第一入口是课程学习：沿九模块路线观看
黑马程序员原课程、阅读站内讲义与 StudyPilot 真实代码、向课内 AI 导师提问并完成
练习。计划、任务和 Agent 监督用于辅助教学闭环，而不是产品本体。

阶段 9 已完成首节端到端示范课，Vue、Java 和 Python 可以通过真实公共 API 形成
“课程—导师—练习—掌握度”闭环。完整产品范围见
[产品需求说明](docs/studypilot-product-requirements.md)，前端架构和最新公共契约见
[前端开发对接说明](docs/前端开发对接说明.md)。早期 Java 实现记录仍保留在
[后端开发说明](后端开发说明.md)。

默认学习顺序和首批黑马程序员/官方资料见
[Java + AI 基础学习路线](docs/studypilot-java-ai-starter-syllabus.md)。教程推荐优先
检索黑马程序员，技术版本、兼容性和 API 事实优先采用最新官方文档。

## 仓库结构

```text
backend/     Spring Boot：账户、学习计划、任务、测验、权限与审计
ai-service/  FastAPI：RAG、模型路由、Agent 编排与工具调用
web/         Vue 3：学习工作台与 Agent 对话界面
infra/       Docker Compose、环境模板与部署配置
docs/        产品、架构、迭代与开发文档
```

## 当前技术基线

- Java 17 + Spring Boot
- Python 3.12 + FastAPI + LangGraph + DeepSeek API
- Vue 3 + TypeScript + Vite + Pinia
- MySQL 8 + Flyway
- LangGraph + DeepSeek + Tavily
- FastEmbed + Qdrant 本地混合检索
- 加密 SQLite Checkpointer
- Docker Compose + Nginx + 可选 Prometheus

## 开发原则

- Java 服务是业务事实来源与权限边界；Python Agent 不直接写核心业务数据库。
- 每个功能以一个小的垂直切片交付：先写失败测试，再实现，再本地验证。
- Agent 普通聊天不会自动授权写操作；计划、任务与调整都使用结构化预览和专用确认。

## 快速启动

原生开发按 MySQL → Spring Boot → FastAPI → Vue 的顺序启动。三个服务端安全值
`INTERNAL_SERVICE_TOKEN`、`AI_CREDENTIAL_MASTER_KEY`、`LANGGRAPH_AES_KEY`
首次生成后必须稳定保存。详细命令、Docker Compose、演示数据和五分钟演示路线见
[部署与演示指南](docs/部署与演示指南.md)。

生成一组不含秘密输出的最小演示数据：

```bash
DEMO_PASSWORD='临时强密码' scripts/demo-data.sh
```

## 全量验证

```bash
cd backend
./mvnw test

cd ../ai-service
.venv/bin/python -m pytest -q
.venv/bin/python -m ruff check app tests

cd ../web
npm test -- --run
npm run typecheck
npm run build
```

## 文档入口

- [Roadmap 驱动学习与项目 Agent 设计](docs/superpowers/specs/2026-08-09-roadmap-driven-self-learning-and-project-agent-design.md)
- [Roadmap Domain Foundation 实施计划](docs/superpowers/plans/2026-08-09-roadmap-domain-foundation.md)
- [Roadmap Foundation 端到端联调](docs/roadmap-foundation-e2e.http)
- [系统架构](docs/architecture.md)
- [部署与演示指南](docs/部署与演示指南.md)
- [阶段 8 公共 API 联调](docs/stage8-release-e2e.http)
- [阶段 8 真实验收结果](docs/stage8-e2e-result.md)
- [阶段 9 课程学习联调](docs/course-learning-e2e.http)
- [阶段 9 真实教学验收结果](docs/course-learning-e2e-result.md)
- [前端开发对接说明](docs/前端开发对接说明.md)
- [Java + AI 基础学习路线](docs/studypilot-java-ai-starter-syllabus.md)
- [AI 服务说明](ai-service/README.md)
- [项目开发步骤](项目开发步骤.md)
- [Docker Compose 说明](infra/README.md)

浏览器只调用 Java `/api/**`。`/internal/**` 只用于 Java 与 Python 服务间通信，不能
暴露内部令牌给前端。
