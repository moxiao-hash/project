# StudyPilot 核心知识点教学与面试通关指南

> **适用场景**：本项目可作为投递中大厂 **Java 后端开发**、**AI Agent / LLM 应用工程化**、**全栈开发** 或 **基础架构/云原生研发** 实习与校招的高含金量简历项目。
> **核心特色**：告别市面上泛滥的“电商/外卖/博客管理系统”同质化项目，直击工业级 **“Spring Boot 核心业务与鉴权审计 + FastAPI/LangGraph 状态图智能体编排 + Qdrant 混合检索 RAG + Docker 受治理代码安全沙箱 + 全链路 SSE 流式长连接”** 高门槛架构。

---

## 目录
- [第一部分：项目全局架构与核心技术全景](#第一部分项目全局架构与核心技术全景)
- [第二部分：五大核心模块技术深度解析教学](#第二部分五大核心模块技术深度解析教学)
  - [1. 业务事实中心与服务边界治理（Spring Boot）](#1-业务事实中心与服务边界治理spring-boot)
  - [2. LangGraph 状态图与智能体编排（FastAPI + LangGraph）](#2-langgraph-状态图与智能体编排fastapi--langgraph)
  - [3. 企业级 RAG 混合检索与向量库工程（FastEmbed + Qdrant + RRF）](#3-企业级-rag-混合检索与向量库工程fastembed--qdrant--rrf)
  - [4. 受治理的本地代码测试安全沙箱（Runner Service）](#4-受治理的本地代码测试安全沙箱runner-service)
  - [5. 全链路 SSE 流式通信与高可用长连接（Spring Boot ↔ FastAPI ↔ Vue 3）](#5-全链路-sse-流式通信与高可用长连接spring-boot--fastapi--vue-3)
- [第三部分：大厂实习面试核心问答与深度题解（全真模拟）](#第三部分大厂实习面试核心问答与深度题解全真模拟)
  - [一、 架构设计与选型篇（必问考点）](#一-架构设计与选型篇必问考点)
  - [二、 LangGraph 与 Agent 编排深度篇](#二-langgraph-与-agent-编排深度篇)
  - [三、 RAG 检索增强与向量数据库篇](#三-rag-检索增强与向量数据库篇)
  - [四、 基础架构与代码沙箱安全篇（降维打击考点）](#四-基础架构与代码沙箱安全篇降维打击考点)
  - [五、 数据一致性、并发与可靠性篇](#五-数据一致性并发与可靠性篇)
- [第四部分：实习简历书写模板与亮点梳理](#第四部分实习简历书写模板与亮点梳理)

---

## 第一部分：项目全局架构与核心技术全景

### 1. 业务背景与定位
StudyPilot 是一个基于交互式知识图谱（12 阶段、64 节点）驱动的 Java + AI 沉浸式学习与代码沙箱评测平台。系统通过将学习路线图实体化为有向无环图（DAG），结合自适应 AI 导师监督、自动化测验生成以及本地隔离的代码执行沙箱，形成 **“学习 → 问答 → 生成计划 → 测验 → 真实代码评测 → 掌握度更新”** 的闭环。

### 2. 系统拓扑图

```text
                     ┌─────────────────────────────────────────┐
                     │          Web 前端 (Vue 3 + TS)          │
                     │  Roadmap 图谱 / 对话工作台 / SSE 接收器 │
                     └────────────────────┬────────────────────┘
                                          │ 仅允许请求 /api/** (Bearer JWT)
                                          ▼
                     ┌─────────────────────────────────────────┐
                     │       Java 后端 (Spring Boot 3)         │
                     │ 业务事实来源 / 权限校验 / 事务与审计 /   │
                     │ 凭据加密 / 乐观锁 / SSE 逆向代理转发    │
                     └──────┬───────────────────────────┬──────┘
                            │                           │
  /internal/** (HMAC/Token) │                           │ Unix Domain Socket + HMAC
                            ▼                           ▼
┌─────────────────────────────────────────┐   ┌───────────────────────────────────┐
│       AI 服务 (FastAPI + LangGraph)     │   │      Runner Service (代码沙箱)    │
│  StateGraph 编排 / 人在回路 (HITL) /    │   │  Docker/Podman 隔离 / tmpfs 挂载  │
│  FastEmbed + Qdrant 混合 RAG / 工具调用 │   │  只读源码 / cgroups 限制 / Seccomp│
└────────────┬────────────────────────────┘   └───────────────────────────────────┘
             │
   ┌─────────┴─────────┐
   ▼                   ▼
DeepSeek API      Tavily Search
(大语言模型)       (联网时效检索)
```

### 3. 数据层职责划分原则
- **MySQL 8.0（业务唯一事实来源）**：存储用户凭证、学习计划、路线节点掌握度状态、测验题目与作答历史、操作审计流水。所有写操作必须通过 Java 领域的严格校验。
- **Qdrant（派生向量索引）**：由本地文档、教学大纲切片向量化构建。即使向量库损坏或重置，也可随时通过 MySQL 中的源数据完整重建。
- **加密 SQLite（Agent 状态存储）**：专供 LangGraph 的 `AsyncSqliteSaver` 记录多轮会话状态（Checkpoints）、Thread 会话分支、消息快照，采用应用级 AES 密钥加密，防止对话隐私泄露。

---

## 第二部分：五大核心模块技术深度解析教学

### 1. 业务事实中心与服务边界治理（Spring Boot）

#### 核心知识点：
- **权限与边界最小化原则**：大模型生成的任何结果均属于“不可信输入”。Python AI 服务**绝不允许拥有数据库直连写权限**。所有由 AI 发起的变更（如调整路线、生成学习计划、完成打卡）只能向 Java 后端提交结构化请求（Draft/Preview），由用户在界面显式授权确认后，Java 开启本地数据库事务写入。
- **服务间通信安全（Zero-Trust 内网隔离）**：
  - 前端浏览器**只能**调用 Java 的 `/api/**` 端点。
  - Java 调用 Python 的 `/internal/**`，携带基于高熵共享密钥配置的 `INTERNAL_SERVICE_TOKEN`。
  - 前端无法获取任何后端内部密钥，Java 拦截器统一负责校验 Bearer Token 提取 `ownerId`，杜绝用户通过接口伪造 `ownerId` 水平越权。
- **敏感凭据加密存储（AES-GCM）**：
  - 用户自定义配置的大模型 API Key（如 DeepSeek/OpenAI Key）在存入 MySQL 前，由 Java 使用 AES-256-GCM 算法结合主密钥（`AI_CREDENTIAL_MASTER_KEY`）完成加盐加密。
  - 前端回显时仅返回脱敏掩码（如 `sk-****abcd`），防止凭据外泄。
- **并发控制与版本审计（Optimistic Locking）**：
  - 学习计划和路线图节点包含 `@Version` 乐观锁版本号，防止多端或自动化任务并发更新产生脏写。
  - 引入专用的 `AuditLog` 实体与拦截机制，对所有由 Agent 提议并执行的变更打上标记（来源、变更前快照、变更后快照、操作人 IP）。

---

### 2. LangGraph 状态图与智能体编排（FastAPI + LangGraph）

#### 为什么不用简单的 LangChain 链式（Linear Chain）？
传统的 LangChain `LLMChain` 或线性 LCEL 管道适合简单的单向输入输出，但无法应对生产级 Agent 的核心痛点：
1. **循环与重试（Cycles & Loops）**：工具调用出错后需要重新反思、重试或回退。
2. **人在回路（Human-in-the-loop / HITL）**：大模型生成危险操作（如修改学习进度、删除计划）时，需要图执行暂停（Interrupt），将状态持久化，等待外部系统人工确认后再恢复执行（Resume）。
3. **多状态分支与长短时记忆管理**：多轮对话的状态合并（State Reducer）与分支追踪。

#### 核心状态图架构（StateGraph）：
- **TypedDict State**：定义强类型的 Agent 状态字典，包含 `messages`、`current_plan_draft`、`retrieved_docs`、`safety_check_passed` 等。
- **Checkpointer（状态持久化与断点续传）**：
  - 采用 `AsyncSqliteSaver`，将每一次节点的流转写入带有 `thread_id`（对应前端 `conversationId`）的 Checkpoint。
  - 即使 FastAPI 服务重启，用户刷新浏览器也能无缝恢复上次暂停的执行图。
- **结构化输出保证（Structured Outputs）**：
  - 使用 Pydantic 定义严格的 JSON Schema（如 `PlanDraftOutput`、`QuizGenerationOutput`），配合大模型的 Function Calling / Tool Calling，避免由于模型幻觉生成格式混乱的不可解析文本。
- **敏感数据安全拦截与本地路由**：
  - 内置数据分类器：被标记为 `SENSITIVE` 或 `LOCAL_ONLY` 的学习文档或代码，阻断外发给外部大模型 API 或联网检索服务，强制走本地流程或脱敏。

---

### 3. 企业级 RAG 混合检索与向量库工程（FastEmbed + Qdrant + RRF）

#### 传统 RAG 的致命缺陷：
单一依靠语义稠密向量检索（Dense Embedding），经常面临**“专有名词识别差、精准关键词匹配失准”**的问题；而单纯依赖传统关键词检索（BM25/全文索引），又缺乏同义词泛化与语义理解能力。

#### StudyPilot 的混合检索架构：
1. **文档切片工程（Chunking Strategy）**：
   - 采用层次化递归字符切片（RecursiveCharacterTextSplitter），设置分块大小 500~800 tokens，重叠区域（Overlap）10%~15%，保证代码块与段落上下文语义不被生硬切断。
2. **稠密检索（Dense Retrieval）**：
   - 使用轻量高效的 `FastEmbed`（如 `BAAI/bge-small-zh-v1.5`），在本地 CPU/内存极低开销下完成高质量文本向量生成，写入 Qdrant。
3. **稀疏检索（Sparse / BM25）**：
   - 提取切片中的编程关键字、API 签名、专有名词，建立 BM25 词频统计索引。
4. **倒数排名融合（Reciprocal Rank Fusion - RRF）**：
   - 将 Dense 检索的前 $K$ 个结果与 BM25 检索的前 $K$ 个结果，利用 RRF 公式计算综合得分：
     $$RRF\_Score(d) = \sum_{m \in \{dense, bm25\}} \frac{1}{k + rank_m(d)} \quad (通常设定\ k = 60)$$
   - 该算法无需对不同维度的相似度分值做复杂的归一化校准，即可优雅地融合关键词精确匹配度与语义相关度。
5. **时效性联网增强（Tavily Search Integration）**：
   - 当知识库检索置信度低于阈值或用户明确询问最新开源版本特性时，触发 Tavily 搜索，且搜索结果打上溯源标签，由用户确认后方可持久化到本地资料库。

---

### 4. 受治理的本地代码测试安全沙箱（Runner Service）

#### 为什么必须独立设计 Runner Service？
用户学习 Java、Python 时需要提交代码运行单元测试，如果直接在 Java 或 Python 进程中通过 `Runtime.getRuntime().exec()` 执行命令，极易遭到恶意代码攻击（如 `System.exit(0)` 导致服务崩溃、执行 `rm -rf /` 破坏宿主机、读取本地环境变量或挖矿）。

#### 四重安全治理防线：
1. **通信隔离与身份防伪造**：
   - 仅通过 **Unix Domain Socket（UDS）** 本地通信，避免暴露跨机网络端口（Socket 文件权限严格限定为 `0600`）。
   - Java 端签发基于 **HMAC-SHA256** 的防篡改信封（Payload 包含 command、allowed_root、nonce、timestamp），Runner 端校验签名，并在 SQLite 中记录 `nonce`，严格**防范重放攻击**。
2. **命令与工作目录白名单（Allowlist）**：
   - 严格限定仅执行预置动作模板：`MAVEN_TEST`、`MAVEN_COMPILE`、`NPM_TEST`、`PYTEST`、`PREPARE_DEPENDENCIES`。
   - 宿主机项目路径必须在 `STUDYPILOT_RUNNER_ALLOWED_ROOTS` 白名单内，防止路径遍历攻击（Path Traversal）。
3. **文件系统只读与无污染运行**：
   - 宿主机用户源码目录以 **只读挂载（Read-Only Mount）** 挂载进容器 `/source` 目录；
   - 容器内部使用内存临时文件系统 **tmpfs**（`/workspace`），在启动前将源码拷贝进 tmpfs 中编译执行。测试完毕后容器销毁，**宿主机上的用户源码目录零污染、零变更**。
4. **Linux 容器底层硬隔离（Kernel-level Isolation）**：
   - **Network 隔断**：容器启动携带 `--network none`，运行测试时全封闭断网，杜绝数据外传或下载恶意脚本；
   - **cgroups 限制**：严格限制物理内存配额（如最大 512MB，超额直接触发 OOM-Killed）与 CPU 使用率（如限制使用 1 个物理核心），防止死循环 CPU 100%；
   - **权限剥离**：以非 root 用户（UID 1000）运行，剥离所有特权能力（`--cap-drop=ALL`），启用只读根文件系统（`--read-only`）；
   - **超时熔断**：设置硬超时（如 30 秒），超时自动强制 kill 容器。

---

### 5. 全链路 SSE 流式通信与高可用长连接（Spring Boot ↔ FastAPI ↔ Vue 3）

#### 选型：为什么是 SSE（Server-Sent Events）而不是 WebSocket？
- **单向下行契约高度契合**：大模型生成场景属于典型的“客户端发起一次请求，服务端持续流式返回文本与事件（Thinking 思考过程、Tool Calls、Final Chunk）”，无需 WebSocket 全双工双向开销。
- **基于 HTTP 标准协议**：天然兼容 HTTP/2 多路复用，穿透公司内网代理防火墙友好，支持标准 HTTP 认证头（Authorization Bearer）。
- **轻量与易维护**：无需额外维护复杂的 WebSocket 心跳状态机与握手握退逻辑，天然支持客户端自动断线重连（`retry:` 指令与 `Last-Event-ID`）。

#### 全链路数据流转设计：
1. **Vue 3 前端**：使用封装的 Fetch API（基于 `ReadableStream`）处理 SSE 流，支持流式解析自定义事件帧：
   - `event: thinking`：模型思考推理过程流（Reasoning Content）；
   - `event: tool_call`：正在调用的工具名称与入参；
   - `event: message`：正文 Markdown 增量输出；
   - `event: done`：完成信号与元数据统计（Token 消耗、耗时）。
2. **Spring Boot 聚合网关代理**：
   - 提供 `/api/agent/stream` 统一对外入口，校验用户 JWT 并将请求包装后以 WebClient / RestClient 转发给 Python FastAPI。
   - 转发过程中持续将 Python 端返回的 `text/event-stream` 字节流透传给前端，避免在 Java 内存中缓冲全文，显著降低堆内存占用。
3. **异常中断与状态保全**：
   - 若流传输中途断开，Java 端监听连接关闭事件，向 FastAPI 发送取消执行信号（AbortSignal），节约大模型 Token 成本；
   - 前端保留已收到的部分内容，并根据已存储的 `conversationId` 随时请求历史端点恢复最后快照。

---

## 第三部分：大厂实习面试核心问答与深度题解（全真模拟）

### 一、 架构设计与选型篇（必问考点）

#### Q1：为什么你的项目不全部用 Java（如使用 Spring AI / LangChain4j）实现，而是采用 Java + Python 双语言混合架构？
**标准回答：**
> “我在立项初期对全栈 Java 方案和双语言混合架构做过对比评估：
> 1. **核心业务与数据治理的严谨性**：Java（Spring Boot）在企业级业务建模、事务管理、RBAC 细粒度权限控制、高并发接口防护和审计流水方面具备极高的成熟度和工程规范性。因此我将 Spring Boot 设定为系统的‘业务唯一事实来源’与安全边界。
> 2. **AI 生态与 Agent 图编排的敏捷性**：当前生成式 AI 与 Agent 前沿技术生态几乎全面由 Python 主导。特别是 **LangGraph**，它在有向状态图、带循环回溯（Cyclic Graph）、人在回路（Human-in-the-loop）的断点暂停与持久化恢复方面拥有极高灵活性。相比之下，当时的 Java 端生态（如 LangChain4j / 早期的 Spring AI）更多聚焦于线性的 Chain 或简单的 Prompt 组装，在复杂的多状态回溯与异步 Checkpointer 上支持较为繁琐。
> 3. **职责解耦与关注点分离**：通过微服务架构隔离后，算法逻辑、Prompt 微调和检索策略的迭代只需部署 FastAPI 服务，完全不影响核心用户数据与学习业务资产，且通过私有令牌通信，从物理架构上封死了大模型越权直接污染核心数据库的通道。”

---

#### Q2：Python AI 服务与 Java 后端是如何进行安全通信的？如何防范水平越权和伪造身份？
**标准回答：**
> “系统采取了**双重安全防御设计**：
> 1. **服务间私有通信鉴权**：Python 服务仅开放 `/internal/**` 路由给内部服务调用，请求头中必须包含双方通过环境变量绑定的高熵私有服务令牌 `INTERNAL_SERVICE_TOKEN`。该令牌禁止暴露给前端，且在 Nginx 网关层直接拦截外部用户对 `/internal/**` 的所有公网访问。
> 2. **用户身份唯一派生机制（防水平越权）**：客户端发送的所有请求均只携带用户自身的 JWT Bearer Token 请求 Java 网关 `/api/**`。Java 拦截器解密 Token 并提取当前认证用户的 `ownerId`，然后由 Java 在内网通信信封中隐式传递给 Python 服务。Python 服务绝对不相信任何客户端提交的外部 ID 参数，从而在根本上杜绝了攻击者通过修改参数窥探他人会话的水平越权风险。”

---

### 二、 LangGraph 与 Agent 编排深度篇

#### Q3：详细讲讲你们的 LangGraph 是怎么编排的？什么是“人在回路（Human-in-the-loop）”？
**标准回答：**
> “在我们的 `ai-service` 中，我们将学习计划的制定抽象为一个状态图（`StateGraph`）：
> - **状态定义（AgentState）**：保存多轮会话的 `messages` 列表、待生成的计划草案（`PlanDraft`）、检索证据（`Context`）以及当前所处的流程节点。
> - **节点流转（Nodes & Edges）**：包含 `retrieve_context`（知识库检索）、`generate_draft`（调用大模型生成草案）、`human_approval`（等待用户确认）以及 `execute_commit`（调用 Java 写入库）等节点。
> - **人在回路的实现原理**：
>   在大模型生成计划草案后，图执行到达 `human_approval` 节点，触发 LangGraph 的 `interrupt()` 机制挂起当前执行。此时，LangGraph 会通过 `AsyncSqliteSaver` 将包含当前执行游标和状态快照的 Checkpoint 序列化写入本地数据库，并向前端返回结构化的预览草案。
>   当用户在前端界面仔细核对、修改并点击‘确认保存’后，前端发起确认请求，系统通过相同的 `thread_id` 调用 `resume()` 唤醒状态图，继续流向下一个写库节点并完成审计记录。”

---

#### Q4：大模型经常出现“幻觉”或者输出格式不符合预期，你们是如何在工程层面约束它的？
**标准回答：**
> “我们采用了**三层硬防御机制**：
> 1. **基于 Pydantic 的强制结构化 Schema 约束**：使用 OpenAI 兼容的 Function Calling / Tool Call 模式，向模型提供基于 Pydantic 定义的严格 JSON Schema（明确字段类型、枚举值范围、必填项与字段说明），强迫大模型按固定格式输出。
> 2. **语法校验与自动重试节点（Self-Correction Loop）**：在 LangGraph 中设计了条件边（Conditional Edge）。模型输出结果首先经过验证函数，如果 JSON 反序列化失败或缺少关键字段，不会直接向前端报错，而是将校验错误信息动态包装为 Prompt 反馈给模型，在状态图中触发原地重试（设置最大重试次数为 2 次）。
> 3. **业务逻辑防线**：所有由大模型生成的草案，在最终写入 Java MySQL 之前，必须经过 Spring Boot 层的 `Validator` 业务规则校验（如计划日期范围、先修节点依赖合法性），任何不合规数据直接在业务层拦截。”

---

### 三、 RAG 检索增强与向量数据库篇

#### Q5：你们的 RAG 是怎么做的？为什么选择 FastEmbed + Qdrant，而不是把向量存到 MySQL 或者直接用全量 Prompt 喂给模型？
**标准回答：**
> “1. **全量 Prompt 的弊端**：全量输入会导致 Context Window 剧烈膨胀，带来高昂的 API 调用开销与显著的响应延迟，且长文本中容易发生‘Lost in the Middle’（中间信息被模型忽视）问题。
> 2. **向量数据库选型考量**：Qdrant 是一款采用 Rust 编写的高性能向量数据库，原生支持 HNSW（分层导航小世界图）近似最近邻搜索与 Payload 过滤（如基于用户资料权限标签过滤）。它支持本地以轻量级文件/嵌入式方式运行，单机内存开销小且查询延迟低，远优于在传统关系型数据库上打补丁式扩展。
> 3. **FastEmbed 本地轻量化**：传统方案调用 Python 的 PyTorch/Sentence-Transformers 往往需要加载臃肿的运行环境。而 FastEmbed 基于 ONNX Runtime 运行量化后的 Embedding 模型，无需 GPU，单核 CPU 上单次向量化仅需几十毫秒，内存占用控制在几百兆以内，非常适合单机或容器化环境部署。”

---

#### Q6：什么是 RRF（倒数排名融合）？它在你们的混合检索中解决了什么问题？
**标准回答：**
> “**痛点背景**：在学习场景中，用户常问两类问题，一类是概念性模糊提问（如‘怎么理解 Spring 的循环依赖？’），此时稠密向量检索效果好；另一类是精准代码/报错提问（如‘ClassNotFoundException: org.springframework.boot.SpringApplication’），此时向量检索常由于切词后语义平均化而召回不准确，必须依赖 BM25 词频精确匹配。
> 
> **RRF 解决方案**：
> 传统混合检索需要为向量相似度分值（0~1 的余弦值）和 BM25 检索得分（可能大于几十的分值）设置权重超参数加权求和，调参成本极高且在不同数据集上极不稳定。
> **RRF（Reciprocal Rank Fusion）** 则摒弃了绝对分数，只关注排序名次。通过计算：
> $$RRF\_Score = \frac{1}{k + rank_{dense}} + \frac{1}{k + rank_{bm25}}$$
> 为两路召回结果按名次衰减打分并重新排序（通常取常数 $k=60$）。它具有天然的免参数归一化优势，能够自适应地将‘语义相关且包含关键专有名词’的优质切片排在最前面，大幅提升了召回准确率（Top-K Recall）。”

---

### 四、 基础架构与代码沙箱安全篇（降维打击考点）

#### Q7：你们是如何设计代码测试沙箱（Runner Service）的？如果有人提交恶意代码（如死循环、挖矿、读取系统文件），你怎么防范？
**标准回答：**
> “这是我们系统技术壁垒最高的一个基础架构模块。我们从**‘通信认证、文件隔离、内核资源治理、权限剥离’**四个维度构筑了防御机制：
> 1. **UDS 本地通信与防重放**：Runner 仅通过本地 Unix Socket 监听通信，杜绝暴露网络端口。Java 端使用 HMAC-SHA256 对包含当前时间戳和随机 nonce 的信封进行签名，Runner 校验签名并把 nonce 暂存 SQLite，有效防御越权调用与重放攻击。
> 2. **源文件防污染（Read-Only + tmpfs）**：宿主机用户源码以只读方式挂载到容器的 `/source`，容器启动时入口脚本在纯内存文件系统 `tmpfs`（`/workspace`）中进行拷贝和编译测试。测试结束后销毁容器，宿主机源码物理上不可能被篡改。
> 3. **Linux cgroups 资源硬隔离**：
>    - **CPU 限制**：设定 `--cpus=1.0`，即使提交了死循环代码，也仅消耗一个物理核心，不会拖垮宿主机器；
>    - **内存限制**：设定 `--memory=512m --memory-swap=512m`，一旦用户代码恶意申请大内存，直接触发 Linux 内核 OOM-Killer 强行终止容器；
>    - **超时熔断**：主进程设置 30 秒硬超时，超时直接强制发送 SIGKILL。
> 4. **特权降级与网络断绝（Seccomp & Network none）**：
>    - 容器参数开启 `--network none`，完全拔除虚拟网卡，无法进行任何网络外联或向外传输数据；
>    - 开启 `--read-only` 只读根文件系统，添加 `--cap-drop=ALL` 剥离 Linux 内核特权，使用非 root 专用测试账号（UID 1000）运行，从根本上防止 Docker 提权与容器逃逸。”

---

### 五、 数据一致性、并发与可靠性篇

#### Q8：大模型生成的学习任务，在并发或者网络重试场景下，如何保证幂等性？
**标准回答：**
> “我们在 Spring Boot 业务层设计了**组合幂等锁机制**：
> 1. **业务唯一键与防重 Token**：在前端发起生成或确认调整请求时，Java 网关会派发一个唯一的 `actionId`（基于 UUID + 用户 ID），写入 Redis/内存防重表并设置有效 TTL。同一个 `actionId` 重复提交直接拒绝。
> 2. **领域业务唯一定位索引**：对于定期的周期性检查或打卡调整，我们在数据库中维护了 `(user_id, plan_id, trigger_date, trigger_type)` 的唯一约束。如果同一触发原因在同一天已经成功应用过一次调整，后续的并发请求会在数据库层面由于唯一索引冲突而抛出特定业务异常，触发回滚并给出幂等友好提示。
> 3. **乐观锁版本控制（Optimistic Locking）**：核心计划与节点状态表包含 `version` 字段，更新执行时使用 `WHERE id = ? AND version = ?`。如果在当前事务读取到提交期间该计划已被其他请求变更，本次提交直接失败并提示版本过期，避免后发请求覆盖先发更新。”

---

#### Q9：SSE（Server-Sent Events）长连接在弱网环境下断开，你们的前后端是如何做到优雅重连和状态恢复的？
**标准回答：**
> “1. **断线检测与防状态丢失**：前端封装的流式读取器监听连接的 `onerror` 与流异常终止事件。如果传输非正常完成，前端不会粗暴清空界面，而是将已经完整接收到的数据块（Markdown 文本与工具调用卡片）冻结在 Pinia 响应式状态中。
> 2. **会话级上下文恢复**：每一个对话会话均拥有唯一的 `conversationId`。如果连接因弱网彻底断开，前端在触发重连时，会先通过常规 HTTP GET 请求 Java 的 `/api/agent/conversation/{id}` 端点获取服务端存储的最后一份完整状态快照（包括已持久化的高速 Checkpoint），并将前端缺失的片段补齐，再按需建立新的长连接。
> 3. **后端资源快速释放**：在 Java 转发层，我们监听了客户端连接的 `AsyncListener.onError()` 和连接断开信号。一旦客户端主动刷新或断开连接，Java 立即中断向 Python 发起的下游 HTTP 流，并通知 Python 取消后方的大模型 API 阻塞等待，避免无意义的算力和 Token 资费浪费。”

---

## 第四部分：实习简历书写模板与亮点梳理

### 简历项目模块参考（可直接抄录至简历）

```markdown
项目名称：StudyPilot —— 交互式知识图谱驱动的 Java+AI 学习与沙箱评测平台
核心技术栈：Java 17, Spring Boot 3, Python 3.12, FastAPI, LangGraph, Qdrant, Docker, Vue 3, SSE
项目架构：采用多语言微服务协作架构，Spring Boot 作为业务唯一事实中心与统一鉴权审计网关，
         FastAPI 结合 LangGraph 实现智能体编排与 RAG 检索，独立 Runner 提供受治理的代码执行安全沙箱。

核心成果与职责：
1. 业务与安全体系构建：基于 Spring Boot 搭建核心业务底座，实现 RBAC 用户鉴权与敏感 API 凭据的 AES-256-GCM 
   加盐存储；设计服务间零信任架构，通过私有高熵令牌与只读网关拦截，彻底阻断大模型越权直接写库风险。
2. 智能体编排与人在回路（HITL）：利用 FastAPI + LangGraph 状态图构建自适应学习助手，基于 SQLite 加密 Checkpointer 
   实现会话状态与断点快照的持久化恢复；结合 Pydantic 结构化输出与状态挂起（Interrupt）机制，实现生成任务的结构化
   预览与人工二次确认写操作。
3. 高可用混合检索（RAG）优化：集成 FastEmbed 与 Qdrant 向量数据库，自研“Dense 稠密语义 + BM25 关键词 + 
   倒数排名融合（RRF）”的多路召回混合检索管线，有效解决编程术语精准匹配与语义理解失衡问题，检索准确率显著提升。
4. 容器化代码安全沙箱（Runner Service）：基于 Unix Domain Socket 与 Docker 实现受治理的代码测试评测引擎，
   通过 HMAC-SHA256 信封与 Nonce 机制防御重放攻击；利用 Linux cgroups 限制 CPU 与内存、通过 tmpfs 与只读挂载
   隔离宿主机文件系统，阻断恶意代码破坏与容器逃逸。
5. 全链路流式传输与长连接治理：全流程打通“Python → Java 网关代理 → Vue 3”的 SSE 流式长连接，支撑大模型思考链
   （Reasoning）、工具调用状态卡片与正文 Markdown 的毫秒级平滑渲染，并设计了断网重连与异常熔断取消机制。
```

---

## 学习路线建议：如何彻底吃透本项目？
1. **第一天**：对照 `docs/architecture.md` 与 `README.md`，把系统跑起来，亲身体验一次“登录 → 生成学习计划 → 预览修改 → 确认保存 → 提交测验”的完整闭环。
2. **第二天**：单步 Debug `backend` 中的内部 Token 拦截器与凭据加密解密流程，理解 Spring Boot 是如何死守安全底线的。
3. **第三天**：阅读 `ai-service/app` 中的 LangGraph 状态图定义，看断点（Interrupt）是如何由代码触发的，Checkpointer 是怎么写入 SQLite 的。
4. **第四天**：阅读 `runner-service`，重点看它的 Docker 命令构建参数与 Unix Socket HMAC 签名校验逻辑，搞懂安全容器启动的核心参数。
5. **第五天**：结合第三部分的面经进行模拟问答，将上述技术点转化为自己的表达语言。
