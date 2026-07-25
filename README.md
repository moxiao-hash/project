# StudyPilot

面向个人学习者的 AI 学习执行工作台。用户可以导入学习资料、通过对话建立学习计划、完成任务与测验；在授权边界内，Agent 会整理资料、生成练习并调整后续学习安排。

Java 业务后端第一版已经成型。完整产品范围见 [产品需求说明](docs/studypilot-product-requirements.md)，后端实现和接口索引见 [后端开发说明](后端开发说明.md)。

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
- Python + FastAPI（下一阶段设计）
- Vue 3 + TypeScript + Vite（待初始化）
- MySQL 8 + Flyway
- Docker Compose
- 向量检索、Redis（进入 Python/异步阶段后按需引入）

## 开发原则

- Java 服务是业务事实来源与权限边界；Python Agent 不直接写核心业务数据库。
- 每个功能以一个小的垂直切片交付：先写失败测试，再实现，再本地验证。
- 先完成可用 MVP，再引入 Redis、向量库、GUI 自动化等增强能力。

## 快速验证

```bash
cd backend
./mvnw test
```

完整容器启动方式见 [infra/README.md](infra/README.md)。下一步是基于已经稳定的 Java 业务契约设计 FastAPI Agent 服务。
