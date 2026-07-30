import pytest

from app.agent.grounding import PlanGroundingService
from app.retrieval.models import RetrievedEvidence
from app.search.models import WebSearchOutcome

pytestmark = pytest.mark.anyio


class FakeRetriever:
    async def search(self, owner_id, query):
        return [
            RetrievedEvidence(
                material_id="private-1",
                title="私人日记",
                text="不能发送的正文",
                locator="chunk 1",
                category="NOTE",
                privacy_level="SENSITIVE",
                score=1,
            ),
            RetrievedEvidence(
                material_id="syllabus-1",
                title="公开大纲",
                text="先基础后框架",
                locator="第 1 章",
                category="SYLLABUS",
                privacy_level="NORMAL",
                score=0.8,
            ),
        ]


class FakeWeb:
    def __init__(self):
        self.calls = []

    async def search(self, owner_id, query):
        self.calls.append((owner_id, query))
        return WebSearchOutcome(query=query)


async def test_plan_grounding_excludes_private_text_from_cloud_context() -> None:
    web = FakeWeb()
    service = PlanGroundingService(FakeRetriever(), web)

    result = await service.retrieve("user-1", "请按我的大纲生成计划")

    assert len(result.context) == 1
    assert result.context[0]["title"] == "公开大纲"
    assert "不能发送的正文" not in str(result.context)
    assert "隐私资料" in result.warnings[0]
    assert web.calls == []


class PublicRetriever:
    async def search(self, owner_id, query):
        return []


async def test_plan_tutorial_query_searches_with_itheima_priority() -> None:
    web = FakeWeb()
    service = PlanGroundingService(PublicRetriever(), web)

    await service.retrieve("user-1", "推荐一套 Spring Boot 学习课程")

    assert web.calls == [
        ("user-1", "黑马程序员 itheima B站 推荐一套 Spring Boot 学习课程")
    ]


async def test_plan_version_query_keeps_original_official_search_terms() -> None:
    web = FakeWeb()
    service = PlanGroundingService(PublicRetriever(), web)
    query = "Spring Boot 当前支持哪个 Java 版本？"

    await service.retrieve("user-1", query)

    assert web.calls == [("user-1", query)]
