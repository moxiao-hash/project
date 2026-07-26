"""资料处理阶段使用的稳定领域模型。"""

from dataclasses import dataclass


@dataclass(frozen=True)
class ParsedBlock:
    """从源文件中提取出的语义块及其人类可读位置。"""

    text: str
    locator: str


@dataclass(frozen=True)
class ParsedDocument:
    blocks: tuple[ParsedBlock, ...]


@dataclass(frozen=True)
class MaterialChunk:
    """可以独立建立索引并作为引用返回的最小文本块。"""

    position: int
    text: str
    locator: str


@dataclass(frozen=True)
class MaterialAnalysis:
    summary: str | None = None
    tags: tuple[str, ...] = ()
    knowledge_points: tuple[str, ...] = ()
    warnings: tuple[str, ...] = ()
