# StudyPilot Java + AI 基础学习路线

> 适用对象：moxiao  
> 项目目标：围绕 StudyPilot 本身，逐步掌握 Java 传统后端与 Python AI Agent 的完整开发闭环。  
> 使用规则：本文件是 `SYLLABUS`，决定学习顺序；技术版本、兼容性和 API 细节以最新官方文档为准。

## 一、学习原则

1. 学习范围只覆盖 StudyPilot 当前及近期会使用的技术，不扩展成泛计算机课程。
2. 教程优先采用黑马程序员的中文课程，官方文档负责核实当前版本和补充准确细节。
3. 每学一个主题，都回到 StudyPilot 阅读、调试或手写一个小功能，避免只看视频。
4. 现阶段不追求一次学完全部课程；先达到“能读懂、能修改、能测试”，再逐步深入。
5. 用户后续上传的笔记、课件和代码资料作为补充，不改变本大纲的主顺序，除非用户明确调整。

## 二、总路线

```text
Java 基础
→ Spring Boot 3 与 REST API
→ MySQL 与 JPA
→ Vue 3 与 TypeScript
→ Python 与 FastAPI
→ DeepSeek API 与提示词
→ LangChain/LangGraph 与 Agent
→ RAG、Qdrant 与 Tavily
→ 测试、安全、Git 与 Docker
```

## 三、分阶段课程

### 1. Java 基础

项目对应：`backend/src/main/java` 中的实体、DTO、Service、Controller、异常和测试代码。

最低学习目标：

- 熟悉变量、分支、循环、方法、类、接口、继承、异常、泛型和集合。
- 能读懂 Lambda、Stream、注解、Record、枚举和 `Optional` 的常见用法。
- 能在 IntelliJ IDEA 中断点调试，并能根据堆栈定位到自己项目的代码。
- 能手写一个包含参数校验、业务判断和返回值的小型 Service 方法。

黑马主资料：

- [黑马程序员 Java 学习路线图](https://www.itheima.com/news/20230109/161816.html)
- [黑马程序员 AI 智能应用开发学习路线图](https://www.itheima.com/news/20260525/155938.html)

官方补充：

- [Java 官方学习文档](https://dev.java/learn/)

暂不深入：JVM 调优、复杂并发框架、桌面 GUI 和大型算法专题。

### 2. Spring Boot 3 与 REST API

项目对应：登录鉴权、学习目标、计划、任务、资料、测验、Agent 公共门面和统一异常响应。

最低学习目标：

- 理解 IoC、依赖注入、Bean、分层结构和配置文件。
- 能编写并调用 `GET`、`POST`、`PATCH` REST 接口。
- 掌握 DTO、Bean Validation、全局异常处理和 HTTP 状态码。
- 理解 Spring Security、Bearer Token、用户隔离和 Java/Python 内部服务认证。
- 能使用 JUnit、Mockito 和 Spring Boot Test 验证接口与业务逻辑。

黑马主资料：

- [黑马程序员 Spring Boot 3 + Vue 3 全套视频教程](https://www.bilibili.com/video/BV14z4y1N7pg/)

官方补充：

- [Spring Boot Reference](https://docs.spring.io/spring-boot/index.html)
- [Spring Security Reference](https://docs.spring.io/spring-security/reference/)

暂不深入：Spring Cloud 微服务拆分、消息中间件和 Kubernetes。

### 3. MySQL 与 JPA

项目对应：用户、计划版本、任务历史、资料解析任务、测验、掌握度、授权与审计数据。

最低学习目标：

- 掌握表、主键、外键、索引、事务和常用增删改查 SQL。
- 能理解 StudyPilot 的实体关系与 Flyway 迁移文件。
- 掌握 JPA Entity、Repository、分页、事务边界和乐观锁。
- 能解释幂等键、唯一约束以及任务租约为什么能防止重复处理。
- 能用 MySQL 客户端检查真实落库结果。

黑马主资料：

- [黑马程序员 MySQL 数据库入门到精通](https://www.bilibili.com/video/BV1Kr4y1i7ru/)

官方补充：

- [MySQL 8.0 Reference Manual](https://dev.mysql.com/doc/refman/8.0/en/)
- [Spring Data JPA Reference](https://docs.spring.io/spring-data/jpa/reference/)

暂不深入：分库分表、主从复制和大规模数据库运维。

### 4. Vue 3 与 TypeScript

项目对应：`web` 中的登录、工作台、计划、任务、资料、知识问答、测验、通知、审计和设置页面。

最低学习目标：

- 理解组件、Props、事件、响应式状态、生命周期和 Composition API。
- 掌握 TypeScript 基础类型、接口、联合类型、泛型和空值处理。
- 理解 Vue Router、Pinia、Axios 请求拦截器和页面权限。
- 能从 Java DTO 建立前端类型，并处理加载、空状态、错误、轮询和确认操作。
- 能修改一个页面并通过前端测试、类型检查和构建。

黑马主资料：

- [黑马程序员 Vue 全套视频教程](https://www.bilibili.com/video/BV1zq4y1p7ga/)
- [黑马程序员 TypeScript 教程](https://www.bilibili.com/video/BV14Z4y1u7pi/)

官方补充：

- [Vue 3 Guide](https://vuejs.org/guide/introduction.html)
- [TypeScript Handbook](https://www.typescriptlang.org/docs/handbook/intro.html)

暂不深入：SSR、Nuxt、复杂动画和大型设计系统。

### 5. Python 与 FastAPI

项目对应：`ai-service/app` 中的路由、Pydantic 模型、异步任务、Java 客户端和模型运行时。

最低学习目标：

- 熟悉 Python 类型标注、数据类、异常、协议、异步函数和上下文管理。
- 理解 FastAPI 路由、依赖注入、Pydantic 校验、生命周期和异常映射。
- 能阅读 Java 与 Python 的内部 HTTP 契约，并排查 401、409、422、502、503。
- 能使用 pytest、Fake/Mock 和异步测试验证服务行为。
- 能安全地通过 `.env` 配置 Key，而不把秘密提交到 Git。

黑马主资料：

- [黑马程序员 FastAPI 教程](https://www.bilibili.com/video/BV1zV2QBtE39/)
- [黑马程序员 Python + AI 学习资料](https://www.bilibili.com/video/BV1h1VbzHER2/)

官方补充：

- [FastAPI Tutorial](https://fastapi.tiangolo.com/tutorial/)
- [Python 3 Documentation](https://docs.python.org/3/)

暂不深入：数据科学、模型训练和 Python 桌面应用。

### 6. DeepSeek API 与提示词

项目对应：计划生成、知识问答、资料摘要、测验生成和代码文本评估。

最低学习目标：

- 理解聊天模型的 system/user 消息、结构化输出、温度和 Token。
- 能说明 API 模型与 Ollama 本地模型的取舍。
- 掌握提示词中的角色边界、可信数据与不可信数据隔离、来源引用和失败重试。
- 能确认真实回答来自配置的 DeepSeek 模型，而不是前端固定文本。
- 理解 Key 的服务端加密存储、脱敏显示和额度控制。

黑马主资料：

- [黑马程序员 Java + AI 智能应用开发教程](https://www.bilibili.com/video/BV1gb42177hm/)
- [黑马程序员 AI 智能应用开发学习路线](https://www.itheima.com/news/20260525/155938.html)

官方补充：

- [DeepSeek API Docs](https://api-docs.deepseek.com/)

暂不深入：大模型预训练、微调和 GPU 集群部署。

### 7. LangChain、LangGraph 与 Agent

项目对应：计划对话、任务操作、知识会话、计划调整以及专用确认接口。

最低学习目标：

- 理解模型、Prompt、Tool、State、Node、Edge 和 Checkpointer。
- 能区分普通聊天、候选操作、授权确认与真实执行。
- 理解 Agent 为什么不能直接修改 MySQL，必须通过 Java 业务 API。
- 掌握上下文保存、幂等、并发锁、失败恢复和人机协同 `interrupt`。
- 能画出一次“对话生成计划并确认”的完整调用链。

黑马主资料：

- [黑马程序员 RAG + Agent 实战教程](https://www.bilibili.com/video/BV1yjz5BLEoY/)
- [黑马程序员 Java + AI 智能应用开发教程](https://www.bilibili.com/video/BV1gb42177hm/)

官方补充：

- [LangChain Documentation](https://docs.langchain.com/)
- [LangGraph Documentation](https://docs.langchain.com/oss/python/langgraph/overview)

暂不深入：多 Agent 自治协作、无限循环式自主规划和任意电脑控制。

### 8. RAG、Qdrant 与 Tavily

项目对应：资料解析、分段、Embedding、混合检索、联网搜索、引用回答和计划增强。

最低学习目标：

- 理解 RAG、Embedding、向量相似度、稀疏检索、RRF 和元数据过滤。
- 能解释 MySQL 为什么是规范数据源，Qdrant 为什么是可重建的派生索引。
- 掌握按 `ownerId` 过滤、资料隐私级别和敏感正文不出本地的边界。
- 理解 Tavily 负责联网搜索、DeepSeek 负责组织回答，两者作用不同。
- 能检查回答中的本地资料引用、网页链接、定位片段和降级警告。

黑马主资料：

- [黑马程序员 RAG + Agent 实战教程](https://www.bilibili.com/video/BV1yjz5BLEoY/)

官方补充：

- [Qdrant Hybrid Queries](https://qdrant.tech/documentation/concepts/hybrid-queries/)
- [Tavily Search API](https://docs.tavily.com/documentation/api-reference/endpoint/search)

暂不深入：分布式向量集群、知识图谱和多模态 OCR。

### 9. 测试、安全、Git 与 Docker

项目对应：整个仓库的质量保障、本地运行、审计、秘密管理和发布演示。

最低学习目标：

- 遵循“失败测试 → 最小实现 → 重构 → 全量验证”的开发循环。
- 能运行 Maven、pytest、Ruff、Vue 测试、类型检查和构建。
- 理解认证、授权、SSRF、防提示词注入、加密、脱敏日志和高风险确认。
- 掌握 Git 状态、精确暂存、提交、分支、推送和冲突处理。
- 理解 Dockerfile、Compose、Volume、端口、环境变量和健康检查。

黑马主资料：

- [黑马程序员 Docker 快速入门到项目部署](https://www.bilibili.com/video/BV1vo4y1T73j/)
- [黑马程序员 Java 学习路线图中的 Maven、Git 与 Docker 阶段](https://www.itheima.com/news/20230109/161816.html)

官方补充：

- [Git Reference](https://git-scm.com/docs)
- [Docker Get Started](https://docs.docker.com/get-started/)
- [OWASP API Security Top 10](https://owasp.org/API-Security/)

暂不深入：Kubernetes、复杂 CI/CD 平台和公网高可用集群。

## 四、学习任务生成规则

Agent 根据本大纲生成计划时必须遵守：

1. 优先安排当前项目正在开发或刚完成的模块。
2. 用户明确的可用时间、截止日期和学习强度高于本大纲。
3. 默认顺序按九个阶段推进，但已掌握部分可以用小测或手写任务快速验证后跳过。
4. 每周同时包含“阅读代码、手写实现、运行测试、总结复盘”，手写题难度与比例逐步提高。
5. 推荐课程时优先检索黑马程序员；涉及最新版本、API 和兼容性时优先检索官方文档。
6. 没有可靠来源时必须说明不确定性，不得虚构课程、链接或技术事实。

## 五、完成标准

当用户能够独立完成以下事项时，视为基础路线成型：

- 在 IDEA 中启动并调试 Java、Python、Vue 和 MySQL。
- 解释浏览器 → Java → Python → DeepSeek/Tavily/Qdrant/MySQL 的职责边界。
- 手写一个带测试的 Spring Boot 小功能和一个带测试的 FastAPI 小功能。
- 从资料导入到 RAG 引用问答，再到计划、任务、测验与掌握度完成一次闭环。
- 排查一次认证、模型、联网、数据库或异步任务故障，并说明根因。
- 使用 Git 提交可验证的改动，并能通过项目全量测试。

