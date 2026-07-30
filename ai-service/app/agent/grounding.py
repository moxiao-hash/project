"""为学习计划 Agent 准备可追溯、经过隐私过滤的知识上下文。"""

from dataclasses import dataclass, field
from typing import Protocol

from app.knowledge.models import KnowledgeCitation
from app.retrieval.models import RetrievedEvidence
from app.search.models import WebSearchOutcome
from app.study_scope import (
    build_learning_web_query,
    is_tutorial_query,
    needs_fresh_facts,
)


class PlanRetriever(Protocol):
    async def search(self, owner_id: str, query: str) -> list[RetrievedEvidence]: ...


class PlanWebSearcher(Protocol):
    async def search(self, owner_id: str, query: str) -> WebSearchOutcome: ...


@dataclass(frozen=True)
class PlanGrounding:
    context: list[dict] = field(default_factory=list)
    citations: list[KnowledgeCitation] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)


class PlanGroundingService:
    def __init__(self, retriever: PlanRetriever, web_searcher: PlanWebSearcher) -> None:
        self._retriever = retriever
        self._web_searcher = web_searcher

    async def retrieve(self, owner_id: str, query: str) -> PlanGrounding:
        evidence = await self._retriever.search(owner_id, query)
        private = [
            item
            for item in evidence
            if item.privacy_level in {"SENSITIVE", "LOCAL_ONLY"}
        ]
        normal = [
            item
            for item in evidence
            if item.privacy_level not in {"SENSITIVE", "LOCAL_ONLY"}
        ]
        warnings = (
            ["命中隐私资料；其正文未加入云模型上下文，也未基于本轮问题联网"]
            if private
            else []
        )
        outcome = WebSearchOutcome(query=query)
        if not private and self._needs_web_search(query):
            outcome = await self._web_searcher.search(
                owner_id,
                build_learning_web_query(query),
            )

        context = [
            {
                "source_type": "MATERIAL",
                "category": item.category,
                "title": item.title,
                "locator": item.locator,
                "text": item.text,
            }
            for item in normal
        ]
        context.extend(
            {
                "source_type": "WEB",
                "category": "WEB",
                "title": item.title,
                "url": item.url,
                "text": item.snippet,
            }
            for item in outcome.results
        )
        citations = [
            KnowledgeCitation(
                source_type="MATERIAL",
                material_id=item.material_id,
                title=item.title,
                locator=item.locator,
                snippet=item.text,
            )
            for item in normal
        ]
        citations.extend(
            KnowledgeCitation(
                source_type="WEB",
                result_id=item.result_id,
                title=item.title,
                url=item.url,
                snippet=item.snippet,
            )
            for item in outcome.results
        )
        return PlanGrounding(
            context=context,
            citations=citations,
            warnings=[*warnings, *outcome.warnings],
        )

    @staticmethod
    def _needs_web_search(query: str) -> bool:
        return needs_fresh_facts(query) or is_tutorial_query(query)
