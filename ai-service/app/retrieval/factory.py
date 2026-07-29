"""在资料处理与知识问答之间共享单进程 Qdrant 客户端。"""

from functools import lru_cache

from app.retrieval.hybrid_index import QdrantHybridIndex


@lru_cache
def get_hybrid_index(path: str, cache_dir: str | None = None) -> QdrantHybridIndex:
    return QdrantHybridIndex.persistent(path, cache_dir)
