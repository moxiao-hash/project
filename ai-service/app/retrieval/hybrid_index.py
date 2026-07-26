"""使用 Qdrant dense + sparse 向量和 RRF 的本地混合索引。"""

from dataclasses import dataclass
from pathlib import Path
from typing import Protocol
from uuid import NAMESPACE_URL, uuid5

from fastembed import SparseTextEmbedding, TextEmbedding
from qdrant_client import QdrantClient, models

from app.material.models import MaterialChunk
from app.retrieval.models import IndexMaterial, RetrievedEvidence


@dataclass(frozen=True)
class SparseEmbedding:
    indices: list[int]
    values: list[float]


@dataclass(frozen=True)
class EmbeddingPair:
    dense: list[float]
    sparse: SparseEmbedding


class EmbeddingProvider(Protocol):
    dimension: int

    def embed(self, texts: list[str]) -> list[EmbeddingPair]: ...


class FastEmbedProvider:
    """在本机 ONNX Runtime 中生成多语言 dense 与 BM25 sparse 向量。"""

    def __init__(
        self,
        dense_model: str = (
            "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"
        ),
        sparse_model: str = "Qdrant/bm25",
    ) -> None:
        self._dense = TextEmbedding(model_name=dense_model)
        self._sparse = SparseTextEmbedding(model_name=sparse_model)
        self.dimension = 384

    def embed(self, texts: list[str]) -> list[EmbeddingPair]:
        dense_vectors = list(self._dense.embed(texts))
        sparse_vectors = list(self._sparse.embed(texts))
        return [
            EmbeddingPair(
                dense=dense.tolist(),
                sparse=SparseEmbedding(
                    indices=sparse.indices.tolist(),
                    values=sparse.values.tolist(),
                ),
            )
            for dense, sparse in zip(dense_vectors, sparse_vectors, strict=True)
        ]


class QdrantHybridIndex:
    def __init__(
        self,
        client: QdrantClient,
        embeddings: EmbeddingProvider,
        *,
        collection_name: str = "studypilot_material_chunks",
    ) -> None:
        self._client = client
        self._embeddings = embeddings
        self._collection = collection_name
        self._ensure_collection()

    @classmethod
    def persistent(cls, path: str) -> "QdrantHybridIndex":
        Path(path).mkdir(parents=True, exist_ok=True)
        return cls(QdrantClient(path=path), FastEmbedProvider())

    def upsert(
        self,
        material: IndexMaterial,
        chunks: list[MaterialChunk],
    ) -> None:
        vectors = self._embeddings.embed([chunk.text for chunk in chunks])
        points = [
            models.PointStruct(
                id=str(
                    uuid5(
                        NAMESPACE_URL,
                        f"studypilot:{material.material_id}:{chunk.position}",
                    )
                ),
                vector={
                    "dense": vector.dense,
                    "sparse": models.SparseVector(
                        indices=vector.sparse.indices,
                        values=vector.sparse.values,
                    ),
                },
                payload={
                    "ownerId": material.owner_id,
                    "materialId": material.material_id,
                    "title": material.title,
                    "category": material.category,
                    "privacyLevel": material.privacy_level,
                    "position": chunk.position,
                    "text": chunk.text,
                    "locator": chunk.locator,
                },
            )
            for chunk, vector in zip(chunks, vectors, strict=True)
        ]
        self._client.upsert(self._collection, points=points, wait=True)

    def search(
        self,
        owner_id: str,
        query: str,
        *,
        limit: int = 8,
    ) -> list[RetrievedEvidence]:
        vector = self._embeddings.embed([query])[0]
        owner_filter = models.Filter(
            must=[
                models.FieldCondition(
                    key="ownerId",
                    match=models.MatchValue(value=owner_id),
                )
            ]
        )
        results = self._client.query_points(
            collection_name=self._collection,
            prefetch=[
                models.Prefetch(
                    query=vector.dense,
                    using="dense",
                    filter=owner_filter,
                    limit=max(limit * 3, 20),
                ),
                models.Prefetch(
                    query=models.SparseVector(
                        indices=vector.sparse.indices,
                        values=vector.sparse.values,
                    ),
                    using="sparse",
                    filter=owner_filter,
                    limit=max(limit * 3, 20),
                ),
            ],
            query=models.FusionQuery(fusion=models.Fusion.RRF),
            query_filter=owner_filter,
            limit=limit,
            with_payload=True,
        ).points
        evidence = [
            RetrievedEvidence(
                material_id=str(point.payload["materialId"]),
                title=str(point.payload["title"]),
                text=str(point.payload["text"]),
                locator=str(point.payload["locator"]),
                category=str(point.payload["category"]),
                privacy_level=str(point.payload["privacyLevel"]),
                score=float(point.score),
            )
            for point in results
            if point.payload is not None
        ]
        return sorted(
            evidence,
            key=lambda item: (item.category != "SYLLABUS", -item.score),
        )

    def _ensure_collection(self) -> None:
        if self._client.collection_exists(self._collection):
            return
        self._client.create_collection(
            collection_name=self._collection,
            vectors_config={
                "dense": models.VectorParams(
                    size=self._embeddings.dimension,
                    distance=models.Distance.COSINE,
                )
            },
            sparse_vectors_config={
                "sparse": models.SparseVectorParams(
                    index=models.SparseIndexParams(on_disk=False)
                )
            },
        )
