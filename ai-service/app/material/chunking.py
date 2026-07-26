"""保持来源定位的确定性资料分段。"""

from app.material.models import MaterialChunk, ParsedDocument


class MaterialChunker:
    def __init__(self, max_characters: int = 450, overlap: int = 60) -> None:
        if max_characters <= 0 or overlap < 0 or overlap >= max_characters:
            raise ValueError("分段长度和重叠范围配置无效")
        self._max_characters = max_characters
        self._overlap = overlap

    def split(self, document: ParsedDocument) -> list[MaterialChunk]:
        chunks: list[MaterialChunk] = []
        for block in document.blocks:
            start = 0
            block_chunk = 1
            while start < len(block.text):
                end = min(start + self._max_characters, len(block.text))
                text = block.text[start:end]
                locator = f"{block.locator} / chunk {block_chunk}"
                chunks.append(
                    MaterialChunk(
                        position=len(chunks),
                        text=text,
                        locator=locator,
                    )
                )
                if end == len(block.text):
                    break
                start = end - self._overlap
                block_chunk += 1
        return chunks
