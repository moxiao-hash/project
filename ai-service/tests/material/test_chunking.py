from app.material.chunking import MaterialChunker
from app.material.models import ParsedBlock, ParsedDocument


def test_splits_long_blocks_with_bounded_overlap() -> None:
    text = "".join(str(index % 10) for index in range(900))

    chunks = MaterialChunker(max_characters=450, overlap=60).split(
        ParsedDocument(blocks=(ParsedBlock(text=text, locator="第 2 页"),))
    )

    assert len(chunks) == 3
    assert all(len(chunk.text) <= 450 for chunk in chunks)
    assert chunks[0].text[-60:] == chunks[1].text[:60]
    assert chunks[0].locator == "第 2 页 / chunk 1"


def test_keeps_short_semantic_blocks_together() -> None:
    document = ParsedDocument(
        blocks=(
            ParsedBlock(text="第一章", locator="标题 1"),
            ParsedBlock(text="依赖注入", locator="段落 2"),
        )
    )

    chunks = MaterialChunker().split(document)

    assert [chunk.text for chunk in chunks] == ["第一章", "依赖注入"]
