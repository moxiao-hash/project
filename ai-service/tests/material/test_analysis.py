import pytest

from app.material.analysis import MaterialAnalyzer
from app.material.models import MaterialAnalysis, MaterialChunk


class RecordingCloudAnalyzer:
    def __init__(self) -> None:
        self.calls = 0

    async def analyze(self, title: str, chunks: list[MaterialChunk]) -> MaterialAnalysis:
        self.calls += 1
        return MaterialAnalysis(
            summary=f"{title} 摘要",
            tags=("Spring",),
            knowledge_points=("依赖注入",),
        )


@pytest.mark.anyio
async def test_normal_material_can_use_cloud_analysis() -> None:
    cloud = RecordingCloudAnalyzer()

    result = await MaterialAnalyzer(cloud).analyze(
        "NORMAL",
        "Spring",
        [MaterialChunk(position=0, text="正文", locator="正文 / chunk 1")],
    )

    assert result.summary == "Spring 摘要"
    assert cloud.calls == 1


@pytest.mark.anyio
@pytest.mark.parametrize("privacy", ["SENSITIVE", "LOCAL_ONLY"])
async def test_private_material_never_reaches_cloud(privacy: str) -> None:
    cloud = RecordingCloudAnalyzer()

    result = await MaterialAnalyzer(cloud).analyze(
        privacy,
        "私人笔记",
        [MaterialChunk(position=0, text="不能外发", locator="正文 / chunk 1")],
    )

    assert result.summary is None
    assert result.warnings == ("隐私资料未发送至云端模型，暂不生成 AI 摘要",)
    assert cloud.calls == 0
