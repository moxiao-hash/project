# StudyPilot 阶段 9 交互式课程学习验收结果

> 执行日期：2026-07-30
> 环境：macOS、本机 MySQL、Spring Boot `8080`、FastAPI `8000`、Vue `5173`

## 1. 真实端到端结果

本次使用独立临时账户，通过 Java 公共 `/api/**` 完成整节示范课。浏览器侧没有
`ownerId`、内部服务令牌或模型 Key；Java 仍是课程、进度、测验和掌握度的事实中心。

| 验收项 | 结果 |
|---|---|
| 课程 | `studypilot-java-ai` |
| 课时 | `lesson-rest-controller` |
| 课程来源 | 4 条：黑马视频 2 条、Spring 官方文档 1 条、项目代码 1 条 |
| B 站视频 | `BV14z4y1N7pg` 第 15、16 分 P |
| 课内导师 | `provider=deepseek`，`model=deepseek-v4-pro` |
| 导师回答 | 成功返回 407 个字符的课内解释，不使用固定回答 |
| 检查题 | Java 确定性判分，通过 |
| 课时测验 | DeepSeek 生成 5 题，来源、题型和答案结构校验通过 |
| 测验作答 | `GRADED`，得分 `100.0` |
| 编程题 | DeepSeek 只评价代码文本，不执行代码 |
| 掌握度 | 产生 5 个知识点记录 |
| 课时状态 | `COMPLETED` |
| 完成时间 | `2026-07-30T12:44:13.333981Z` |
| 继续学习 | 当前没有下一节已发布课时，按契约返回 `204 No Content` |

官方站外播放器地址：

```text
https://player.bilibili.com/player.html?bvid=BV14z4y1N7pg&p=15&autoplay=0&danmaku=0
```

播放器不可用时，页面保留
[B 站第 15 分 P 原页面](https://www.bilibili.com/video/BV14z4y1N7pg?p=15)和
[第 16 分 P 原页面](https://www.bilibili.com/video/BV14z4y1N7pg?p=16)。视频由用户
浏览器直接向 B 站加载，不经过 StudyPilot，不消耗 Tavily 或 DeepSeek 额度。

## 2. 联调中发现并修复的问题

1. MySQL 中 `lessons.content_json` 是 `LONGTEXT`，原来的 `@Lob String` 在当前
   Hibernate/MySQL 方言下被校验为不兼容的 CLOB/TINYTEXT。实体现在显式声明
   `columnDefinition = "LONGTEXT"`，并有映射回归测试。
2. DeepSeek 的当前兼容端点拒绝 LangChain 默认使用的
   `json_schema response_format`，课内导师因此曾返回 502。导师结构化输出改为
   `json_mode`，再由 Pydantic 严格校验字段；真实回答随后成功。
3. 全部已发布课时完成后，`ResponseEntity.ofNullable(null)` 实际返回 404，与产品
   契约不符。继续学习接口现显式返回 204，并增加 Controller 回归测试。

## 3. 自动化验证

```text
Java:   112 tests passed
Python: 233 tests passed, Ruff passed
Vue:    56 tests passed, vue-tsc passed, production build passed
Git:    diff whitespace check passed
```

Python 仍有一条第三方 Starlette/httpx 生命周期弃用警告；Vue 仍有 Node
`localStorage` 实验警告和一条测试路由提示，均不影响通过结果。

## 4. 当前限制

- 九个技术模块均已展示，但当前只发布
  “Controller、REST API 与参数校验”一节完整示范课，其余模块仍为路线占位。
- B 站是否允许播放由其站外播放器、地区、Cookie 和平台策略决定；不可用时只能
  跳转原页面。StudyPilot 不下载视频、字幕，也不伪造观看秒数。
- 已整理课程与讲义的打开、阅读不调用外部 AI；只有用户主动提问、生成测验、评价
  代码或显式搜索时才消耗对应额度。
- 编程题为 `AI_EVALUATED` 文本评价，不保证代码可以编译或运行。
- 真实验收的临时账户和学习记录保留在本机开发数据库，生产演示前可单独清理。
