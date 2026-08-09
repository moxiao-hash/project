# StudyPilot 路线驱动自学平台与项目 Agent 设计

**日期：** 2026-08-09

**状态：** 用户已确认方向，规格审查通过

**适用范围：** StudyPilot 下一轮产品重构及后续 Agent 能力演进

## 1. 摘要

StudyPilot 从“提供具体课程内容的学习网站”重构为个人使用的 Java + AI 自学平台。
平台提供稳定、可视化、循序渐进的学习路线，把路线拆成阶段和知识节点，再根据用户基础、
可用时间、掌握度和复习需要，滚动生成未来 7 天的学习任务。具体课程和教程由用户自行
寻找；平台负责告诉用户学什么、为什么学、做到什么程度，并通过学习打卡和测验共同验证
学习结果。

项目同时承担第二个目标：它本身就是 Java + AI 智能应用的实践载体。Java 后端是一套
完整、独立可用的传统业务系统；Python Agent 是通过稳定 API 和工具契约接入的增强插件。
最终 Agent 不仅能操作 StudyPilot 的全部业务功能，还能在严格授权、审计和本地沙箱约束下，
读取、修改、测试和构建 StudyPilot 项目，并逐步扩展到浏览器和桌面操作。

本设计替代“站内复制课程、嵌入固定视频、把平台做成课程市场”的方向。已有 Course/Lesson
能力先进入兼容期，不立即删除数据库表或历史数据。

## 2. 产品目标

### 2.1 核心目标

1. 为 Java + AI 智能应用开发提供一条版本化、可追踪的标准主路线。
2. 以思维导图和列表两种方式展示完整路线、依赖关系、当前位置和掌握状态。
3. 将完整路线转化为滚动 7 日计划，而不是一次生成数月不可调整的任务。
4. 让每个知识节点形成“学习目标 → 自主找资料 → 实践 → 打卡总结 → 测验 → 复习”的闭环。
5. 用高频开发场景和实用能力评估学习效果，降低冷知识和机械记忆的权重。
6. 允许用户通过 AI 快速发现新知识点及相关网络资源，并在确认后加入路线或近期计划。
7. 保持 Java 业务系统在 Python、DeepSeek、Tavily 不可用时仍可完成核心人工操作。
8. 逐步实现可操作业务、代码仓库、浏览器和桌面环境的项目 Agent。

### 2.2 成功标准

- 用户只看路线和每日任务，就能知道下一步应该学习和实践什么。
- 用户不能仅靠点击“完成”跳过学习验证；节点必须同时满足打卡与测验通过条件。
- AI 推荐的资源有明确链接、来源和推荐理由，搜索失败时不会伪造联网结果。
- AI 生成的计划不能打乱前置依赖，也不能覆盖用户的时间上限。
- 关闭 Python 服务后，路线浏览、每日计划、人工打卡和传统业务仍可工作。
- 浏览器永远不直接调用 Python `/internal/**`，也接触不到内部令牌。
- Agent 的任何业务写入仍由 Java 校验；项目文件修改、命令执行和高风险操作均可预览、确认和审计。

## 3. 非目标

本轮设计不包含：

- 通用学科、教师端、班级、排行榜、课程售卖或多人协作；
- 复制黑马程序员或其他机构的视频、字幕、讲义和付费内容；
- 自建完整课程播放器或保证第三方视频可以站内嵌入；
- 让 Python 直接连接 MySQL 或绕过 Java 修改核心业务数据；
- 让模型自行决定并执行破坏性操作；
- 立即删除现有 Course/Lesson 表、接口和历史学习数据；
- 在当前 DeepSeek 无视觉能力时承诺可靠的任意桌面视觉操作；
- 一开始就建设微服务、消息队列、Kubernetes 或复杂的多 Agent 集群。

## 4. 已确认的产品原则

### 4.1 路线是稳定骨架，计划是动态投影

完整学习路线包含阶段、节点和前置依赖，是可版本化但不会每天变化的课程骨架。用户始终
可以查看完整路线。系统只把未来 7 天的可执行内容投影成每日任务，并根据完成情况、失败
测验、可用时间和用户反馈继续滚动调整。

AI 可以调整节奏、复习安排和未来未开始的任务，可以缩短已掌握的基础节点，但不得：

- 打乱强制前置依赖；
- 跳过阶段毕业要求；
- 修改已经开始或完成的历史任务；
- 突破用户设置的每日时间上限；
- 把可选扩展强行变成主路线阻塞项。

### 4.2 平台提供学习框架，不提供固定课程正文

每个节点提供学习目标、重点、常见错误、实践要求、搜索关键词和建议资料类型。用户自行
选择外部课程、官方文档、文章或其他资料。用户可以保存链接、上传笔记，并在学习完成后
提交自己的总结和实践成果。

### 4.3 打卡和测验缺一不可

节点完成条件固定为：

```text
有效学习打卡存在
AND
节点测验成绩 >= 70
AND
节点要求的必交实践成果已验收通过（若该节点要求）
```

打卡不是简单按钮。有效打卡至少包含学习总结、耗时和自我感受；可选包含资料链接、笔记和
实践成果。测验失败不会清除打卡，而是把节点置为待复习状态，生成复习与重测安排。

### 4.4 Java 是业务事实中心，AI 是可拔插增强层

路线、任务、打卡、测验、掌握度、授权、审计和用户数据都以 Java/MySQL 为准。Python
只通过 Java 内部 API 读取经授权的上下文，生成候选、解释、检索和编排工具调用。任何业务
写操作都必须回到 Java，由 Java 做归属、版本、幂等、风险和业务规则校验。

## 5. 课程路线设计

### 5.1 路线来源与校准规则

路线覆盖参考黑马程序员公开的 Java + AI 学习路线，但只借鉴知识覆盖和先后关系，不复制
具体课程内容：

- [黑马程序员 2026 AI 应用开发学习路线](https://yun.itheima.com/subject/aiappmap/index.html)
- [黑马程序员 Java+Python 双核 AI 应用开发介绍](https://www.itheima.com/news/20260408/140909.html)

版本事实、API 用法和安全规则以对应技术的官方文档为准。黑马资料适合校准学习顺序和中文
学习资源，官方文档适合校准当前版本与准确行为。两者冲突时，平台明确展示冲突并优先采用
官方版本事实。

### 5.2 标准主路线

主路线由 12 个阶段组成：

1. **Java 核心与工程基础**：语法、面向对象、集合、异常、泛型、IO、并发、JVM 基础、Maven、单元测试。
2. **Spring Boot 传统业务开发**：分层架构、REST、参数校验、异常处理、配置、日志、文件、定时任务。
3. **MySQL 与数据访问**：SQL、索引、事务、MyBatis、MyBatis-Plus、JPA、数据建模和并发更新。
4. **Redis、安全、测试与后端工程化**：缓存、JWT/认证授权、接口文档、集成测试、幂等、审计、监控基础。
5. **Vue 与交付基础**：Vue 3、TypeScript、状态管理、路由、前后端联调、Git、Linux、Nginx、Docker。
6. **Python 与 FastAPI 插件服务**：Python 工程化、Pydantic、异步、FastAPI、Java/Python 内部契约。
7. **DeepSeek 与模型工程**：模型 API、提示词、结构化输出、成本、超时、重试、降级和安全输入处理。
8. **LangGraph、Tools 与 MCP Agent**：状态图、短期记忆、工具调用、Human-in-the-loop、MCP；Spring AI 作为 Java 原生对照选修。
9. **RAG、联网搜索与智能测评**：资料解析、向量检索、Qdrant、Tavily、引用、测验和掌握度。
10. **业务操作 Agent**：把 Java 业务 API 暴露为受治理工具，通过对话完成查询、预览、确认和执行。
11. **Developer Agent**：本地代码读取、检索、补丁、测试、构建和 Git 操作，以及浏览器自动化。
12. **沙箱、防火墙与发布**：Runner 隔离、权限、命令策略、密钥、网络边界、部署、恢复和可观测性。

MyBatis 与 JPA 都属于主路线。StudyPilot 当前核心业务继续使用 JPA，不进行无收益的整体
重写；新增一个合适的报表或读模型模块使用 MyBatis/MyBatis-Plus，以形成真实对照实践。

MQ、Elasticsearch、Spring Cloud、分布式事务和 Kubernetes 放入高级可选分支，不作为
完成 Java + AI 主线的前置阻塞条件。

### 5.3 路线节点

每个节点至少包含：

- 标题、唯一编码和所属阶段；
- 必修或选修标记；
- 前置节点集合；
- 学习目标和可观察的完成标准；
- 高频开发重点和常见错误；
- 建议学习时长、实践时长和难度；
- 搜索关键词与建议资料类型；
- 实践成果要求；
- 测验蓝图：知识点、题型范围、实用性权重；
- 路线模板版本。

节点只描述“应该学会什么”，不保存受版权保护的课程正文。节点可引用公开来源作为路线
依据，但这些引用不等于平台托管课程。

### 5.4 入门诊断与阶段毕业

首次使用时，用户先绑定一个已发布的路线版本，再填写技能自评并完成该版本的 5–10 题诊断，
默认 8 题。绑定只建立空的用户路线快照，不会提前排课；诊断完成后才首次生成七日计划。
题库不足 8 题时使用
全部可用题，但不得少于 5 题；不足 5 题时不生成低可信诊断，用户从路线起点开始，之后可在
题库补齐后重新诊断。诊断结果可以：

- 缩短基础节点建议时长；
- 把已掌握节点标记为“待快速验证”；
- 调整未来 7 日的难度和练习比例。

诊断不能直接授予阶段毕业。每个阶段毕业必须同时满足：

```text
全部必修节点完成
AND
阶段综合测验通过
AND
阶段小项目成果已验收通过
```

## 6. 领域模型与数据边界

### 6.1 新 Roadmap 领域

采用真实 Roadmap 领域，不复用或改名 Course/Lesson：

| 实体 | 职责 |
|---|---|
| `RoadmapTemplate` | 标准路线元数据、版本、发布状态 |
| `RoadmapStageTemplate` | 模板中的阶段、顺序、毕业规则 |
| `RoadmapNodeTemplate` | 节点目标、重点、实践和测验蓝图 |
| `RoadmapNodePrerequisite` | 节点之间的有向前置关系 |
| `UserRoadmap` | 用户选择的路线及绑定模板版本 |
| `UserRoadmapNode` | 用户节点状态、进度、掌握度和可用性 |
| `LearningCheckIn` | 学习总结、耗时、自评、链接和成果引用 |
| `RoadmapBranch` | 用户确认加入的可选学习分支 |
| `RoadmapBranchNode` | 可选分支节点、顺序及其对主路线/分支节点的前置依赖 |
| `RoadmapDiagnostic` | 入门诊断会话、答案、得分、能力分布和采用的建议 |
| `LearningArtifact` | 节点实践或阶段项目成果、版本和验收状态 |
| `StageGraduation` | 阶段综合测验、项目成果和毕业结果的不可变快照 |
| `ResourceSearch` | 搜索请求、规范化查询、额度、状态和追踪信息 |
| `ResourceCandidate` | 临时资源卡、来源、URL、推荐理由和过期时间 |
| `ResourceBookmark` | 用户确认收藏的资源及关联节点/可选分支 |
| `RoadmapUpgrade` | 模板升级差异、节点映射、确认和执行结果 |

诊断与阶段综合测验复用 `Quiz`/`QuizAttempt` 的判分能力，通过 `quizPurpose` 区分
`DIAGNOSTIC`、`NODE`、`STAGE_GRADUATION`；诊断另外保存能力分布和排程建议。节点成果与
阶段成果统一保存为 `LearningArtifact`，通过作用域和目标 ID 区分。

`LearningTask`、`Quiz`、`QuizAttempt`、`MasteryRecord`、`Material`、`AgentExecution`、
`AuthorizationGrant`、`Notification` 和 `AuditLog` 继续复用，并增加 `roadmapNodeId` 或
等价关联。动态计划是 Roadmap 节点的执行投影，不成为第二套路线事实。

关键约束包括：模板内 `stageCode` 唯一、模板内 `nodeCode` 唯一、前置关系不得成环、一个
用户对同一路线版本只有一个有效 `UserRoadmap`、一个用户对一个节点只有一条当前进度、
收藏以 `(ownerId, canonicalUrl, roadmapNodeId)` 去重，升级操作和搜索保存操作都使用稳定
幂等键。所有可并发修改的用户进度、任务、成果和升级记录带乐观锁版本。

成果模板声明 `evaluationMode`：`PRESENCE` 由 Java 在文件类型、大小、必填说明和链接格式
全部满足后确定性验收；`AI_RUBRIC` 由 DeepSeek 返回结构化评分候选，Java 校验固定 Rubric
并在总分至少 70 时验收；未来 `RUNNER_TEST` 只有隔离 Runner 的测试报告通过后才验收。
`SUBMITTED` 只代表等待验收，绝不满足节点完成或阶段毕业。AI 不可用时允许成果保持
`SUBMITTED`，用户可继续其他不依赖该成果的学习。

### 6.2 模板与用户快照

已发布模板不可原地覆盖。路线更新创建新版本；现有用户默认继续绑定原版本，并由系统生成
升级差异。用户主动确认后才能升级，避免节点移动导致历史进度失真。

用户节点保存的是模板节点引用和用户状态，不复制大段模板内容。为了历史可解释性，打卡、
测验和任务记录保存当时的节点版本或蓝图版本。

跨版本映射使用不可变的稳定 `nodeCode`。普通文案、时长和重点调整可自动映射；节点拆分、
合并、删除或改变强制前置关系必须出现在升级预览中：

- 一对一且完成标准等价的节点可以保留完成状态；
- 拆分节点只把旧证据挂到新节点，不能自动把多个新节点标为完成；
- 合并节点只有所有必需来源节点都完成时才能继承完成状态；
- 删除节点保留为历史只读，不映射到无关节点；
- 无法确定的映射标记 `MANUAL_REVIEW`，系统不得猜测。

升级使用 `(userRoadmapId, targetTemplateVersion)` 作为幂等边界，在一个 Java 事务中保存升级
快照、映射结果和新用户节点。失败时整体回滚，旧路线仍可使用。升级记录永久保存，支持
展示差异和审计；回退不是删除记录，而是通过独立的、同样需确认的版本切换操作完成。

### 6.3 Course/Lesson 兼容策略

- 从主导航移除 `/courses` 和 `/lessons`，新功能不再依赖它们。
- 旧 API 标记为兼容接口，禁止扩展新的产品能力。
- 旧表和历史进度暂时保留，不执行破坏性迁移。
- 使用显式 `lessonId → nodeCode` 映射表；没有映射的旧 URL 打开只读历史页，不跳到错误节点。
- 旧 `COMPLETED` 课时转换为 `LEGACY_LESSON_PROGRESS` 历史证据，不伪造学习总结、测验通过
  或新节点完成。用户可通过快速验证补齐新节点完成条件。
- 旧课时中的真实笔记、链接和附件以 `LEGACY` 来源挂到对应节点；旧测验和掌握度记录保留
  原始时间与来源，不重复计分。
- 迁移作业以 `(ownerId, lessonId, migrationVersion)` 幂等，逐条记录成功、跳过和失败，支持
  根据迁移批次撤销新增映射数据，但绝不删除原始 Course/Lesson 数据。
- 只有在映射验证、数据库备份、回滚演练和至少一个完整兼容版本结束后，才单独设计旧表清理。

## 7. 状态机

### 7.1 用户节点的正交状态

打卡、测验和成果是可以按任意顺序发生的独立事实，不能压缩成一个互斥枚举。
`UserRoadmapNode` 使用以下正交字段：

| 字段 | 值 |
|---|---|
| `availabilityStatus` | `LOCKED / AVAILABLE` |
| `learningStatus` | `NOT_STARTED / SCHEDULED / IN_PROGRESS` |
| `checkInStatus` | `MISSING / SUBMITTED` |
| `quizStatus` | `NOT_GENERATED / GENERATING / READY / EVALUATING / PASSED / FAILED / PARTIALLY_GRADED` |
| `artifactStatus` | `NOT_REQUIRED / MISSING / SUBMITTED / ACCEPTED / REJECTED` |
| `completionStatus` | `INCOMPLETE / COMPLETED` |

Java 根据这些事实派生前端展示状态：

| 条件 | 展示状态 |
|---|---|
| 前置未满足 | `LOCKED` |
| 可用但未安排 | `AVAILABLE` |
| 已进入七日计划但未开始 | `SCHEDULED` |
| 已开始且尚未满足完成条件 | `IN_PROGRESS` |
| 测验失败或部分判分 | `REVIEW_REQUIRED` |
| 打卡存在但测验尚不可用 | `QUIZ_PENDING` |
| 所有完成条件满足 | `COMPLETED` |

以下顺序全部合法：先打卡后测验、先测验后打卡、先提交成果后打卡。每次提交打卡、测验
最终判分或成果验收时，Java 在同一事务内锁定/乐观更新用户节点，并重新计算：

```text
completionStatus = COMPLETED
当且仅当
checkInStatus = SUBMITTED
AND quizStatus = PASSED
AND artifactStatus IN (NOT_REQUIRED, ACCEPTED)
```

并发更新使用节点版本号；版本冲突返回 409，调用方读取最新事实后重试，不覆盖另一项已提交
结果。`COMPLETED` 默认不可由 AI 直接回退。需要重新学习时创建复习任务和新证据，不篡改
历史完成记录。

### 7.2 每日任务状态

继续使用现有任务状态语义，并扩展路线关联：

```text
TODO → IN_PROGRESS → COMPLETED
  ├───────────────→ DEFERRED
  └───────────────→ SKIPPED
```

只有未来且未开始的 `TODO` 任务允许自动重排。延期、跳过和完成都产生任务历史；节点是否
完成仍由打卡、测验和成果共同决定，不能直接等同于任务 `COMPLETED`。

### 7.3 Quiz 与 QuizAttempt 状态

`Quiz` 描述一次生成的题目集合：

```text
GENERATING → READY
     └────→ GENERATION_FAILED
```

`QuizAttempt` 描述一次独立作答：

```text
SUBMITTED → EVALUATING → GRADED
                    └──→ PARTIALLY_GRADED
```

每个节点测验默认 5 题。蓝图为每题分配整数满分，单题 10–30 分且总和必须为 100，否则
Java 拒绝保存。单选和多选按答案集合精确匹配，得到该题满分或 0 分；代码/解释题的模型
Rubric 得分为 0–100，题目得分公式为
`questionScore = rubricScore / 100 × questionMaxScore`。所有必评题完成后，Java 汇总 0–100
总分，并在 attempt 上保存 `result = PASSED / FAILED`：总分 `>= 70` 为 `PASSED`，否则为
`FAILED`。`PARTIALLY_GRADED` 永远不能通过，也不把未评代码题
写入掌握度。

失败或部分判分后创建新的 `QuizAttempt`；题目可以按同一蓝图重新生成，但需避开最近一次
题目签名。历史 attempt 不覆盖。模型不可用时，打卡事实仍保持 `SUBMITTED`，节点的
`quizStatus` 保持 `NOT_GENERATED` 或 `EVALUATING`，前端派生为 `QUIZ_PENDING`。

同一用户、同一 Quiz 同一时刻只允许一个 `EVALUATING` attempt；相同幂等键返回原 attempt，
不同请求返回 409。用户节点的通过资格采用“存在至少一个有效 `PASSED` attempt”，一旦通过
便单调保持 `quizStatus = PASSED`。通过后的再次练习会保存新 attempt 和掌握度证据，即使
新 attempt 失败也不回退已完成节点；界面可以单独提示“本次复习未通过”。题目被管理员式
数据修复判为无效时，只通过带审计的专用修复流程重新计算资格，普通 AI 工具无权撤销。

## 8. 滚动七日学习编排

### 8.1 每日学习包

每个计划日包含一个或多个学习包。每个学习包依次展示：

1. 节点目标与预计用时；
2. 高频开发重点、常见错误和搜索关键词；
3. 自主查找资料或 AI 资源推荐入口；
4. 一个可验证的实践任务；
5. 学习打卡和总结；
6. 5 题节点测验、解析和必要的复习安排。

默认测验为 5 题，及格线 70 分。题型和难度按节点蓝图、掌握度和阶段位置调整；日常开发
高频点、代码理解、常见错误、调试和实际场景的权重高于名词背诵。

### 8.2 排程优先级

滚动计划使用确定性规则生成候选，AI 只提供难度和解释建议：

```text
用户可用时间与每日上限
> 到期复习和失败测验
> 强制前置依赖
> 主路线阶段顺序
> AI 难度与节奏建议
> 可选分支
```

创建或调整任务时必须校验每日容量、计划周期、任务版本和幂等键。超出已授权范围的调整进入
现有治理确认流程。

“未来 7 天”固定为用户设置时区中的 `today` 到 `today + 6`（含首尾）。Java 使用可注入
`Clock` 计算当前绝对日期，并把 `currentDate`、`timezone` 和每个任务的绝对日期传给 Python；
禁止询问模型“今天是哪天”或让模型自行推算系统日期。

滚动计划采用增量刷新而非整表替换：

- 用户每天首次打开工作台时检查一次窗口；跨日则补齐新进入的第 7 天；
- 节点完成、测验失败、用户修改可用时间后触发一次去抖动刷新；
- 手动刷新只重新计算未来、未开始的 `TODO` 任务；
- `IN_PROGRESS`、`COMPLETED`、`SKIPPED` 和历史日期任务永不自动移动；
- `DEFERRED` 到窗口外的任务继续持久化，日期进入窗口后重新显示；到期复习优先占用容量；
- 刷新按 `(ownerId, windowStart, reason, sourceVersion)` 幂等 upsert，不删除无法容纳的任务，
  而是将其保留为未排期候选并说明原因。

### 8.3 失败后的复习

测验未通过时：

- 保留已完成的打卡；
- 返回逐题解析、错误知识点和改进建议；
- 生成复习任务候选和重测时间；
- 不把节点标为完成；
- 重测使用同一蓝图但避免机械重复相同题目；
- 多次失败时降低单次范围，增加示例和实践，不无限增加日任务量。

## 9. AI 学习助手与资源发现

### 9.1 节点上下文对话

节点内 AI 助手获得以下受控上下文：

- 当前阶段、节点目标、重点和完成标准；
- 用户可用时间和当前任务；
- 用户主动提交的链接、笔记、总结和错误记录；
- 允许发送到云模型的检索片段；
- 最近的对话历史。

它可以解释概念、举例、给提示、分析错误和建议下一步，但不得声称已经阅读用户未上传的
资料，也不得把自由回答当作业务写入授权。平台保留一个全局 AI 入口处理跨阶段问题。

### 9.2 “我想学新知识点”流程

用户可直接提出“我想学习 Redis”或“帮我找 MyBatis-Plus 的入门资料”。流程为：

```text
识别主题
→ 查询当前路线是否已有节点
→ 判断前置知识与适合位置
→ 读取缓存并按需联网搜索
→ 返回带来源的资源卡片
→ 用户确认保存/收藏/加入计划
→ 路线内复用原节点，路线外创建可选分支
```

资源卡片包含：标题、来源、类型、适合程度、预计时间、推荐理由、URL、搜索时间和可能的
时效警告。搜索结果默认是当前对话的临时证据，不自动入库或修改路线。

普通保存链接只保存规范化 URL、用户标题和用户摘要，AI 不会自动读取链接正文。只有用户
显式选择“导入并分析网页”时，才进入现有网页资料导入管线，并继续执行 HTTP/HTTPS 限制、
DNS 与重定向复检、内网/回环/链路本地拦截、内容类型、大小、字符上限和版权约束。

### 9.3 搜索策略与额度控制

- 黑马程序员适合作为中文路线、教程和视频搜索的优先来源。
- 框架版本、API、兼容性和安全事实优先查询官方域名。
- 查询键由 `ownerScope + locale + normalizedQuery + domainPolicy` 组成；规范化包括 Unicode
  归一化、转小写、合并空白和移除不影响语义的末尾标点。
- 教程/路线查询默认缓存 7 天，版本/“最新”事实默认缓存 24 小时，均可通过服务端配置调整。
- 只有用户明确要求新搜索、缓存过期或问题涉及最新事实时调用 Tavily。
- 每条用户消息最多调用 Tavily 1 次、最多保留 5 条结果，不让模型循环搜索。
- 默认每用户每日 20 次、每自然月 200 次软额度，可配置；自然日和自然月按用户设置时区
  计算。显式“重新联网搜索”可以绕过缓存，但仍计入额度且每条消息只能一次。
- 额度耗尽时，混合问答返回 `WEB_SEARCH_QUOTA_EXHAUSTED` 警告并继续使用本地证据；纯搜索
  接口返回 429 和同名稳定错误码，不伪造搜索结果。
- Tavily 不可用时返回缓存、建议搜索词和明确降级警告。
- 不抓取或存储受版权保护的完整视频和课程正文。

### 9.4 测验 Grounding

测验依据优先级为：

```text
当前节点目标与用户约束
> Roadmap 节点测验蓝图和 SYLLABUS
> 用户主动提供的资料、总结和链接
> 经确认的普通资料与网页证据
> 模型常识补充
```

每道题至少引用当前 Roadmap 节点及其测验蓝图这一规范来源；资料和网页只能作为附加引用。
“模型常识”只允许帮助组织措辞，不能伪装成资料或网页来源，也不能单独支撑一道题。每道题
关联一个或多个知识点。选择题由 Java 精确判分；代码或解释题由 DeepSeek
按固定 Rubric 做文本评估，必须标记 `AI_EVALUATED` 以及“未执行代码，不保证可编译或
运行”。需要真实运行代码的能力必须等待隔离执行沙箱。

## 10. 总体系统架构

```text
Vue 3 / TypeScript
        │ Bearer Token，仅调用 /api/**
        ▼
Spring Boot / Java
认证、权限、路线、任务、打卡、测验、掌握度、治理、审计
        ├────────内部服务认证、超时、幂等────────┐
        ▼                                        ▼
FastAPI / Python Agent
LangGraph、DeepSeek、RAG、Tavily、工具编排
        │                              Local Runner（后续、本机隔离）
        ▼                                        │
Qdrant 派生索引                                  ▼
                                      文件 / Shell / Git / Browser / IDE

MySQL 仅由 Java 作为核心业务事实源管理
```

### 10.1 Java 传统后端

即使 Python 完全关闭，Java 仍负责并支持：

- 登录、用户和设置；
- 路线、阶段、节点和依赖查询；
- 七日任务的确定性生成与人工调整；
- 打卡、链接、笔记和实践成果；
- 已生成测验的提交、选择题判分和历史查询；
- 掌握度、通知、授权、审计和管理规则。

AI 不可用时仅影响需要新模型输出的能力，例如生成新测验、生成解释、智能搜索和自然语言
操作，不影响传统页面和已有数据。

### 10.2 Python Agent 插件

Python 负责：

- 结构化模型调用和上下文编排；
- RAG、联网搜索和引用融合；
- 测验候选、代码文本评估和学习解释；
- 自然语言意图识别；
- 把 Java 暴露的受治理业务 API 封装为工具；
- 生成 Local Runner 和浏览器工具的结构化候选，由 Java 确认并执行。

Python 不负责用户认证真相、不直接写业务表、不自行判定越权，也不持有前端身份来源。Java
从登录会话注入 `ownerId`，前端请求不得自行指定它。

## 11. Agent 能力分层

### 11.1 业务操作 Agent

第一层 Agent 只操作 Java 暴露的业务工具，例如：

- 查询路线、今日任务、节点和掌握度；
- 创建计划调整候选；
- 完成、跳过或延期任务；
- 创建打卡草稿；
- 生成测验、复习任务或资料搜索候选；
- 查询执行状态、通知和审计。

这里的“查询可直接执行”只指经过用户隔离的 Java 业务查询，不包括项目文件、Git 输出或
Shell。写操作先形成结构化预览，再根据风险等级和授权调用专用确认接口。聊天文本中的
“确认”不能替代确认 API。

### 11.2 Developer Agent 与 Local Runner

第二层使用独立 Local Runner，不把文件系统和 Shell 能力直接放进公网 FastAPI：

```text
Python Agent
→ 生成结构化工具候选并提交 Java
→ Java 保存 AgentExecution、做策略校验并取得用户确认
→ 只有 Java 可以通过 Unix Socket 调用 Local Runner
→ Runner 在批准的项目根目录内执行
→ Runner 将差异、日志和结构化结果返回 Java
→ Java 原子更新执行状态和审计，再把结果返回 Python/前端
```

Python 进程没有 Runner Socket 的文件权限，也不属于允许访问 Runner 的操作系统用户组，
因此不能绕过 Java 确认链。Java 调用 Runner 时发送签名执行信封，至少包含 `executionId`、
用户、工具名、规范化参数、工作区 ID、风险等级、确认时间、过期时间和随机 nonce。Runner
使用仅与 Java 共享的本机密钥验签，在持久化执行日志中原子消费 nonce；签名错误、超过默认
10 分钟确认有效期或重复 nonce 一律拒绝。

Runner 能力逐步开放：

1. 只读：列文件、搜索、读取、查看 Git 状态和日志；
2. 受控修改：应用补丁、格式化，执行前后显示 diff；
3. 受控命令：测试、构建和白名单开发命令；
4. Git：暂存、提交、推送按风险分别确认；
5. 浏览器：优先 Playwright 和 DOM；
6. IDE/桌面：优先无障碍树，键鼠仅作接口不存在时的最后手段。

任意命令、路径和参数都视为不可信输入。Runner 执行规则固定为：

- 使用 `realpath` 解析工作区和目标，拒绝 `..`、绝对越界路径、符号链接逃逸和未授权嵌套仓库；
- 命令使用“可执行文件 + 参数数组”，不通过 shell 拼接；工具与参数分别使用白名单校验；
- 默认禁止出站网络和新监听端口，需要网络的工具使用独立策略并再次确认；
- 子进程使用精简环境变量白名单，只保留必要的 `PATH`、语言和工具链变量，不继承项目密钥；
- 默认只读命令超时 30 秒、测试/构建 10 分钟，均可按工具缩短；超时先终止进程组，再强制
  清理全部子进程；
- 单次标准输出和错误输出合计最多 2 MB，超出部分截断并标记，不允许撑爆内存或日志；
- 同一工作区最多一个写执行；读执行也不得与写执行并发；
- 删除、数据库写入、Git 推送、发布、网络开放和键鼠操作需要高风险逐次确认。

项目读取使用独立的 `PROJECT_READ` 风险策略，必须经过不可绕过的敏感数据出口：

- 规范化并解析真实路径后，默认拒绝 `.env*`、`application.properties`、
  `application-*.yml/yaml`、PEM/私钥、SSH、云凭据目录、密码库和服务令牌文件；
- `read`、`search`、`git diff/log`、测试、构建和所有命令输出统一执行凭据前缀、高熵字符串、
  私钥块和已登记密钥指纹扫描；Runner 先脱敏，Java 出口再做第二次扫描；
- 输出在拼接、分块和常见编码解码后复扫；命令白名单不提供 `base64`、`xxd` 等可用于绕过
  敏感路径策略的通用编码/读取命令；
- 命中敏感内容时不返回原文，普通结果只保留 `[REDACTED:type]`；无法安全脱敏的结果整体
  标记 `LOCAL_ONLY`，Java 禁止将其传给 Python、DeepSeek、Tavily 或远程日志；
- 确实需要查看敏感文件时使用专用高风险确认，只能由 Java 的本地 UI 临时展示，不能进入
  Agent 消息、模型上下文、剪贴板自动化或持久化执行输出；
- Git diff 和构建日志即使来自非敏感路径也必须扫描，避免凭据已经被误写入普通源码。

### 11.3 Spring AI 的位置

当前 Python + FastAPI + LangGraph 技术栈足以实现上述 Agent，无需迁移到 Spring AI。
Spring AI 放在阶段 8 的选修对照节点，用于学习 Java 原生 Tool Calling、MCP 和模型接入；
除非未来出现明确的 Java 内嵌部署收益，否则不重写已经稳定的 Python 编排层。

## 12. 网络与安全边界

### 12.1 部署边界

- 公网只暴露 Nginx 和 Java 公共 API。
- FastAPI 绑定 `127.0.0.1:8000` 或容器内部网络，不允许公网入站。
- Local Runner 只使用 Unix Socket 或 loopback，不提供公网 HTTP 端点。
- macOS 和服务器防火墙明确拒绝外部访问 FastAPI 与 Runner 端口。
- Vue 只能访问 Java `/api/**`，不能持有 `X-Internal-Service-Token`。
- Java 公共入口启用 TLS、认证、限流、审计和严格 CORS。
- 部署验收必须使用 `lsof`/`ss` 和外部主机探测证明 FastAPI 只监听 loopback、Runner 没有
  公网监听，并验证防火墙规则在重启后仍生效。

### 12.2 密钥与隐私

- DeepSeek、Tavily 和其他 API Key 只提交给 Java 的设置接口。
- 浏览器不持久化、不回显明文 Key；只显示是否配置和脱敏尾号。
- Java 加密保存用户凭据，并在内部调用时短时提供或代理调用。
- `SENSITIVE` 和 `LOCAL_ONLY` 资料正文不发送到 DeepSeek 或 Tavily。
- 日志、异常、审计和模型提示不得包含密码、完整 Key 或内部令牌。

### 12.3 授权治理

沿用现有 AgentExecution 与授权体系：

- 读操作通常为低风险，可自动执行并审计；
- 普通任务调整可在明确的长期授权范围内自动执行；
- 高风险业务写入必须逐次确认；
- 文件修改必须展示补丁；
- 命令、Git 推送、删除、数据库写入、发布和桌面控制必须使用专用确认；
- 确认默认 10 分钟过期；候选内容、参数或工作区变化后旧确认立即失效；
- 重复确认使用稳定幂等键返回原结果，不能重复产生副作用。

## 13. API 边界

以下是领域级契约，具体 DTO 和分页字段在实施计划中按现有项目风格细化。

### 13.1 Java 公共 API

```text
POST /api/roadmap-enrollments
GET  /api/roadmaps/current
GET  /api/roadmaps/current/map
GET  /api/roadmaps/current/stages/{stageId}
GET  /api/roadmaps/current/nodes/{nodeId}
POST /api/roadmaps/current/diagnostics
GET  /api/roadmaps/current/diagnostics/latest
GET  /api/roadmaps/current/upgrades
POST /api/roadmaps/current/upgrades/{upgradeId}/confirm
POST /api/roadmap-nodes/{nodeId}/check-ins
GET  /api/roadmap-nodes/{nodeId}/check-ins
POST /api/roadmap-nodes/{nodeId}/artifacts
GET  /api/roadmap-nodes/{nodeId}/artifacts
GET  /api/learning-artifacts/{artifactId}/evaluation
POST /api/roadmap-stages/{stageId}/artifacts
GET  /api/roadmap-stages/{stageId}/graduation
GET  /api/roadmap-stages/{stageId}/quizzes/latest
POST /api/roadmap-stage-quizzes/{quizId}/attempts
POST /api/roadmap-stages/{stageId}/graduation-attempts
GET  /api/roadmap-nodes/{nodeId}/quizzes/latest
POST /api/roadmap-node-quizzes/{quizId}/attempts
POST /api/roadmap-nodes/{nodeId}/quiz-retries
POST /api/roadmap-nodes/{nodeId}/schedule-previews
POST /api/roadmap-node-schedule-previews/{previewId}/confirm
GET  /api/learning-plans/rolling
POST /api/learning-plans/rolling/refresh
GET  /api/reviews
GET  /api/resource-bookmarks
DELETE /api/resource-bookmarks/{bookmarkId}
GET  /api/roadmap-branches
POST /api/roadmap-branch-previews/{previewId}/confirm
```

`POST /api/roadmap-enrollments` 绑定一个已发布模板版本；重复请求返回同一有效绑定。
`previewId` 是尚未产生业务分支的临时候选，确认后才创建 `branchId`，因此不存在两个分支
确认入口。成果提交接口返回当前验收状态；需要人工/AI 评价的成果未验收前不满足毕业条件。
`graduation-attempts` 只在所有必修节点、阶段测验和阶段成果均已满足时执行确定性毕业校验，
不负责生成测验。

现有任务、测验、掌握度、资料、Agent 门面、授权、通知和审计接口继续使用，并逐步增加
`roadmapNodeId`。公共请求中的用户身份统一取自 Bearer Token，不接受 `ownerId`。

### 13.2 Java 公共 AI 门面

```text
POST /api/agent/knowledge/conversations
POST /api/agent/knowledge/conversations/{id}/messages
POST /api/agent/resource-searches
POST /api/agent/resource-searches/{id}/bookmarks
POST /api/agent/roadmap-branches/preview
POST /api/agent/node-quizzes/generate
POST /api/agent/stage-quizzes/generate
POST /api/agent/learning-artifacts/{artifactId}/evaluate
POST /api/agent/executions/{id}/confirm
```

AI 门面只生成资源、分支或测验候选。分支的实际确认统一调用公共业务接口
`POST /api/roadmap-branch-previews/{previewId}/confirm`；资源收藏返回 `bookmarkId`，可用公共
删除接口取消收藏。测验生成完成后由公共 Quiz/Attempt 接口负责作答和重测。

`PRESENCE` 成果由 Java 提交接口同步验收；`AI_RUBRIC` 成果通过 AI 门面异步评价，Java 校验
结构化 Rubric 后自动写入 `ACCEPTED` 或 `REJECTED`，这是低风险学习评价，不需要写操作
确认但必须审计模型与版本。阶段综合测验由 `stage-quizzes/generate` 生成，再通过公共查询和
attempt 接口完成；毕业接口不会暗中调用模型。

路线内已有节点的“加入近期计划”先创建 `schedule-preview`，返回建议日期、容量影响和冲突；
确认后触发滚动计划增量刷新。确认可选分支也触发同一刷新，并返回 `SCHEDULED` 或
`WAITING_CAPACITY`；容量不足时保留分支/节点但绝不突破每日上限。收藏资源本身不触发排课。

Java 负责身份注入、参数和权限校验、风险登记、超时映射和错误脱敏，再调用 Python 内部
接口。前端不感知内部服务令牌。

### 13.3 Python 内部接口

Python 接口位于 `/internal/**`，只接受 Java 内部认证，包括：

- 节点学习对话与全局对话；
- 资源搜索、路线位置判断和可选分支候选；
- 节点测验生成和代码文本评估；
- 七日计划增强建议；
- 业务工具编排和执行状态。

所有内部请求包含 Java 已验证的用户和资源标识、相关版本、调用 ID 和幂等键。Python 不从
公网请求头推断用户身份。

## 14. 前端信息架构

### 14.1 路由

```text
/roadmap
/roadmap/stages/:id
/roadmap/nodes/:id
/today
/reviews
/mastery
/agent
/materials
/settings
```

`/courses` 和 `/lessons` 在兼容期从导航中隐藏。历史链接可跳转到对应 Roadmap 节点或显示
只读兼容页。

### 14.2 关键页面

**工作台：** 今日学习包、路线总体进度、待复习、周统计、快速询问新知识点。

**路线图：** 阶段主干与节点分支，显示已完成、当前、可学习、锁定、待复习和可选分支。

**阶段页：** 阶段目标、必修节点、毕业条件、综合测验和项目成果。

**节点工作区：** 目标、重点、常见错误、搜索建议、资源卡、笔记/链接、实践、打卡、AI 助手和测验。

**今日页：** 按执行顺序展示未来 7 日中今天的学习包，并允许在规则内延期。

**复习页：** 失败知识点、到期复习、错误解析和重测。
**Agent 页：** 业务操作、资源搜索和全局问答；高风险操作展示结构化预览。

路线图必须同时提供可访问的列表视图，不能只依赖画布、颜色或鼠标拖拽。可选分支使用虚线，
锁定节点必须说明前置条件。

## 15. 故障、降级与一致性

| 故障 | 系统行为 |
|---|---|
| FastAPI 不可达 | 路线、计划、任务和打卡继续；AI 功能显示暂不可用，不伪造答案 |
| DeepSeek 超时或限额 | 保存请求状态并允许重试；测验停留 `QUIZ_PENDING` |
| Tavily 未配置或失败 | 使用未过期缓存与搜索关键词，显示联网降级警告 |
| Qdrant 索引损坏 | MySQL 仍为事实源；允许重建派生索引 |
| 任务版本冲突 | 返回 409，刷新最新数据并重新生成候选，不覆盖他人/旧版本修改 |
| 重复提交或确认 | 稳定幂等键返回原记录，不重复创建任务、分支或执行历史 |
| Python 重启 | 已保存业务数据不丢失；内存对话按现阶段限制重新创建 |
| Local Runner 不可达 | 不执行项目操作，保留候选与审计，不降级为不受控 Shell |

所有跨服务错误对用户返回稳定错误码、可操作说明和追踪 ID。内部异常和模型原始响应不直接
暴露给浏览器。

## 16. 测试与验收

### 16.1 Java

- 模板版本不可变、用户升级和节点依赖校验；
- 稳定 `nodeCode` 的一对一、拆分、合并、删除和人工复核升级映射；
- 路线、节点、打卡、任务和测验的用户隔离；
- 打卡、测验和成果以任意顺序及并发提交时的正交状态和完成规则；
- 用户时区、跨日窗口、增量刷新、延期跨窗口、排程优先级、每日容量和只调整未来任务；
- 测验题目分值合计、选择题确定性判分、代码加权、部分判分及多次 attempt；
- 节点通过资格单调保持、并发 attempt 冲突、成果三种验收模式和阶段毕业前置校验；
- 幂等、乐观锁、通知、治理和审计；
- Python 不可达时传统业务仍可用；
- Course/Lesson 映射、无映射只读页、幂等迁移、批次回滚和原数据保留。

### 16.2 Python

- 节点上下文不会混入其他用户或节点数据；
- 资源搜索的路线内复用、路线外分支候选和前置判断；
- 查询规范化、7 天/24 小时缓存、单轮 1 次/5 条限制、用户软额度和失败降级；
- 黑马教程优先与官方版本事实优先的冲突策略；
- 测验蓝图、高频实用性权重、引用一致性和输出校验；
- 隐私资料不会调用 DeepSeek/Tavily；
- Prompt Injection 不能覆盖系统规则、Rubric 或工具权限；
- 工具只生成候选，未经确认不产生受控写副作用。

### 16.3 前端与端到端

- 路线图与列表状态一致，锁定原因明确；
- 从诊断到七日计划、打卡、测验、复习、节点完成和阶段毕业完整闭环；
- 从“我想学 Redis”到资源卡、用户确认和路线分支/计划加入完整闭环；
- FastAPI 关闭后仍可浏览路线、调整人工任务和打卡；
- 浏览器网络记录中不存在对 Python `/internal/**` 的请求和内部令牌；
- 高风险 Agent 操作必须经过专用确认，重复确认无重复副作用；
- Local Runner 不能访问批准根目录之外的路径。
- Runner 拒绝 `..`、绝对越界路径、符号链接、未授权嵌套仓库、命令注入和未授权网络；
- Runner 不泄露环境密钥，能清理超时进程组、截断超大输出，并拒绝过期/重放执行信封；
- 同一工作区并发执行遵守读写互斥，Python 操作系统身份不能访问 Runner Socket。
- `.env`、Spring 配置、PEM、Git diff 和构建日志中的测试密钥不会进入 Python/模型上下文；
- 敏感字符串跨输出分块、常见编码或拼接出现时仍被拦截，专用本地查看结果标记 `LOCAL_ONLY`。

### 16.4 关键验收场景

```text
新用户绑定一个发布版本的 Java + AI 路线
→ 完成该版本的基础自评和诊断
→ 查看完整思维导图
→ 获得未来 7 天计划
→ 自行选择资料完成首个节点实践
→ 提交总结打卡
→ 完成 5 题测验并达到 70 分
→ 节点变为 COMPLETED，下一依赖节点解锁
→ 测验失败时进入复习而不是完成
→ 询问一个路线外知识点并收到可追溯资源卡
→ 确认后创建可选分支，不打乱主路线
```

## 17. 迁移与发布顺序

1. 建立 Roadmap 模板、阶段、节点、依赖和用户路线数据结构。
2. 发布第一版 Java + AI 标准路线并提供诊断、路线图和列表查询。
3. 建立滚动 7 日计划与 Roadmap 节点关联。
4. 建立打卡、成果、节点测验和节点完成状态机。
5. 将前端主导航切换到路线、今日、复习和掌握度。
6. 接入节点 AI 助手、资源搜索、缓存和可选分支确认。
7. 迁移可用的 Course/Lesson 历史记录，旧功能进入只读兼容期。
8. 扩展业务操作 Agent，使所有传统页面操作具备对话式工具入口。
9. 建立 Local Runner，依次开放只读、补丁、测试构建、Git、浏览器和桌面工具。
10. 完成防火墙、沙箱、恢复、监控和发布验收后，再考虑旧表清理。

每一步都必须采用测试先行、小步提交和真实端到端验证。Roadmap 重构不能与 Developer
Agent 一次性混在同一大提交中。

## 18. 风险与控制

| 风险 | 控制措施 |
|---|---|
| 路线过大导致用户看不懂 | 阶段主干 + 当前节点聚焦 + 未来 7 日投影 |
| AI 随意改变学习顺序 | 依赖和容量由 Java 确定性校验 |
| 测验偏冷知识 | 节点蓝图、高频开发权重和来源审计 |
| 外部资料质量不稳定 | 来源类型、推荐理由、官方事实优先和用户确认 |
| Tavily 额度消耗 | 查询缓存、意图触发、结果条数和每轮次数上限 |
| 版权风险 | 只保存路线摘要、链接和用户自有笔记，不复制课程正文 |
| 旧数据丢失 | 兼容期、映射验证、备份和非破坏性迁移 |
| AI 成为系统单点 | Java 保留完整传统操作和降级路径 |
| Runner 越权 | 本地隔离、根目录限制、白名单、确认、审计和防火墙 |
| 键鼠操作不可靠 | API/DOM/无障碍树优先，键鼠仅最后手段 |
| 无视觉模型能力 | 当前只承诺结构化文件、Shell、DOM 和无障碍操作 |

## 19. 最终架构结论

StudyPilot 的核心不是课程播放器，也不是单纯监督用户的打卡工具，而是一套面向 Java + AI
方向的学习操作系统：稳定路线定义能力边界，滚动计划安排每日行动，打卡记录真实学习，测验
验证掌握度，AI 负责解释、检索、生成候选和操作增强。

技术上继续采用 Java + Python，而不是为了“更像 Java + AI”强行迁移到 Spring AI。Java
保持完整传统后端，Python LangGraph 作为 Agent 插件；未来通过受控 Local Runner 获得类似
开发助手的项目操作能力。该边界既能支持当前学习平台，也为最终“通过对话操作整个项目”
保留清晰、可审计、可逐步实现的演进路径。
