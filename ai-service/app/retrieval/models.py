"""检索索引的输入和输出契约。"""

from dataclasses import dataclass


@dataclass(frozen=True)
class IndexMaterial:
    material_id: str
    owner_id: str
    title: str
    category: str
    privacy_level: str


@dataclass(frozen=True)
class RetrievedEvidence:
    material_id: str
    title: str
    text: str
    locator: str
    category: str
    privacy_level: str
    score: float
