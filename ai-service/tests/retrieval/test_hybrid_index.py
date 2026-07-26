from dataclasses import dataclass

from qdrant_client import QdrantClient

from app.material.models import MaterialChunk
from app.retrieval.hybrid_index import (
    EmbeddingPair,
    QdrantHybridIndex,
    SparseEmbedding,
)
from app.retrieval.models import IndexMaterial


@dataclass
class DeterministicEmbeddings:
    dimension: int = 3

    def embed(self, texts: list[str]) -> list[EmbeddingPair]:
        pairs = []
        for text in texts:
            java = 1.0 if "Java" in text else 0.0
            spring = 1.0 if "Spring" in text else 0.0
            syllabus = 1.0 if "路线" in text else 0.0
            pairs.append(
                EmbeddingPair(
                    dense=[java, spring, syllabus],
                    sparse=SparseEmbedding(
                        indices=[0, 1, 2],
                        values=[java, spring, syllabus],
                    ),
                )
            )
        return pairs


def test_hybrid_query_is_owner_scoped() -> None:
    index = QdrantHybridIndex(
        QdrantClient(":memory:"),
        DeterministicEmbeddings(),
        collection_name="test_materials",
    )
    index.upsert(
        IndexMaterial(
            material_id="material-a",
            owner_id="owner-a",
            title="Java",
            category="LEARNING_MATERIAL",
            privacy_level="NORMAL",
        ),
        [MaterialChunk(position=0, text="Java Spring", locator="正文 / chunk 1")],
    )
    index.upsert(
        IndexMaterial(
            material_id="material-b",
            owner_id="owner-b",
            title="另一个用户",
            category="LEARNING_MATERIAL",
            privacy_level="NORMAL",
        ),
        [MaterialChunk(position=0, text="Java Spring", locator="正文 / chunk 1")],
    )

    results = index.search("owner-a", "Java", limit=5)

    assert [result.material_id for result in results] == ["material-a"]
    assert results[0].locator == "正文 / chunk 1"


def test_syllabus_evidence_is_ranked_before_equal_normal_material() -> None:
    index = QdrantHybridIndex(
        QdrantClient(":memory:"),
        DeterministicEmbeddings(),
        collection_name="test_priority",
    )
    for material_id, category in [
        ("normal", "LEARNING_MATERIAL"),
        ("syllabus", "SYLLABUS"),
    ]:
        index.upsert(
            IndexMaterial(
                material_id=material_id,
                owner_id="owner",
                title=material_id,
                category=category,
                privacy_level="NORMAL",
            ),
            [MaterialChunk(position=0, text="Java 路线", locator="正文 / chunk 1")],
        )

    results = index.search("owner", "Java 路线", limit=5)

    assert [result.material_id for result in results] == ["syllabus", "normal"]
