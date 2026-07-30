"""StudyPilot 的垂直学习范围和联网检索策略。

这些规则集中放在一个模块中，避免计划 Agent、知识问答和后续测验功能
分别维护一套容易漂移的产品边界。
"""

STUDYPILOT_SCOPE = (
    "Java、Spring Boot、MySQL、Vue/TypeScript、Python/FastAPI、"
    "DeepSeek API、LangChain/LangGraph、RAG/Qdrant/Tavily、Git/Docker"
)

STUDYPILOT_SCOPE_POLICY = (
    f"StudyPilot 仅服务于当前 Java + AI 项目的技术栈学习：{STUDYPILOT_SCOPE}。"
    "不要把它扩展成通用学科或泛兴趣学习平台。"
    "教程类资料优先黑马程序员，并使用官方文档交叉验证；"
    "版本、兼容性、当前 API 等时效事实优先官方文档。"
)

FRESH_FACT_MARKERS = (
    "当前",
    "最新",
    "版本",
    "官方",
    "发布",
    "api",
    "today",
    "latest",
    "current",
    "version",
    "release",
)

TUTORIAL_MARKERS = (
    "教程",
    "课程",
    "视频",
    "学习资料",
    "学习路线",
    "路线图",
    "推荐资料",
    "怎么学",
    "tutorial",
    "course",
    "video",
    "roadmap",
)


def needs_fresh_facts(query: str) -> bool:
    """判断问题是否需要优先查询最新或官方事实。"""

    lowered = query.lower()
    return any(marker in lowered for marker in FRESH_FACT_MARKERS)


def is_tutorial_query(query: str) -> bool:
    """判断用户是否明确在寻找课程、视频或学习路线。"""

    lowered = query.lower()
    return any(marker in lowered for marker in TUTORIAL_MARKERS)


def build_learning_web_query(query: str) -> str:
    """为教程检索增加黑马优先提示，但不改写版本事实查询。

    版本问题应让搜索服务优先命中官方资料，不能因为一句话同时包含
    “教程”就被限定在某一家课程来源中。
    """

    if needs_fresh_facts(query):
        return query
    if is_tutorial_query(query):
        return f"黑马程序员 itheima B站 {query}"
    return query
