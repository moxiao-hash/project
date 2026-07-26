"""领取一个 Java 持久化任务并完成资料解析的应用服务。"""

from dataclasses import asdict, dataclass
from typing import Protocol

from app.material.chunking import MaterialChunker
from app.material.models import MaterialAnalysis
from app.material.parsers import MaterialParser, ScannedPdfError
from app.retrieval.models import IndexMaterial


@dataclass(frozen=True)
class ProcessingJob:
    job_id: str
    material_id: str
    owner_id: str
    title: str
    material_type: str
    category: str
    privacy_level: str
    source_url: str | None = None


class ProcessingBackend(Protocol):
    async def claim_material_job(
        self,
        worker_id: str,
        lease_seconds: int,
    ) -> ProcessingJob | None: ...

    async def download_material_content(self, material_id: str) -> bytes: ...

    async def complete_material_job(self, job_id: str, payload: dict) -> None: ...

    async def fail_material_job(
        self,
        job_id: str,
        worker_id: str,
        error: str,
    ) -> None: ...


class MaterialAnalysisService(Protocol):
    async def analyze(
        self,
        privacy_level: str,
        title: str,
        chunks: list,
    ) -> MaterialAnalysis: ...


class MaterialIndex(Protocol):
    def upsert(self, material: IndexMaterial, chunks: list) -> None: ...


class MaterialProcessingService:
    def __init__(
        self,
        backend: ProcessingBackend,
        analyzer: MaterialAnalysisService,
        *,
        worker_id: str,
        parser: MaterialParser | None = None,
        chunker: MaterialChunker | None = None,
        index: MaterialIndex | None = None,
    ) -> None:
        self._backend = backend
        self._analyzer = analyzer
        self._worker_id = worker_id
        self._parser = parser or MaterialParser()
        self._chunker = chunker or MaterialChunker()
        self._index = index

    async def process_once(self) -> bool:
        job = await self._backend.claim_material_job(self._worker_id, 120)
        if job is None:
            return False
        try:
            content = await self._backend.download_material_content(job.material_id)
            document = self._parser.parse(job.material_type, content)
            chunks = self._chunker.split(document)
            analysis = await self._analyzer.analyze(
                job.privacy_level,
                job.title,
                chunks,
            )
            if self._index is not None:
                self._index.upsert(
                    IndexMaterial(
                        material_id=job.material_id,
                        owner_id=job.owner_id,
                        title=job.title,
                        category=job.category,
                        privacy_level=job.privacy_level,
                    ),
                    chunks,
                )
            await self._backend.complete_material_job(
                job.job_id,
                {
                    "workerId": self._worker_id,
                    "summary": analysis.summary,
                    "tags": list(analysis.tags),
                    "knowledgePoints": list(analysis.knowledge_points),
                    "warnings": list(analysis.warnings),
                    "contentReference": (
                        f"qdrant://{job.material_id}"
                        if self._index is not None
                        else f"material://{job.material_id}/chunks"
                    ),
                    "chunks": [asdict(chunk) for chunk in chunks],
                },
            )
        except ScannedPdfError:
            await self._backend.fail_material_job(
                job.job_id,
                self._worker_id,
                "PDF 没有可提取文本层，需要 OCR",
            )
        except Exception:
            await self._backend.fail_material_job(
                job.job_id,
                self._worker_id,
                "资料处理失败，请检查文件格式或稍后重试",
            )
        return True
