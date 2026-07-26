"""把本地同步向量检索安全地移出 FastAPI 事件循环。"""

import asyncio

from app.retrieval.hybrid_index import QdrantHybridIndex
from app.retrieval.models import RetrievedEvidence


class AsyncHybridRetriever:
    def __init__(self, index: QdrantHybridIndex) -> None:
        self._index = index

    async def search(self, owner_id: str, query: str) -> list[RetrievedEvidence]:
        return await asyncio.to_thread(self._index.search, owner_id, query)
