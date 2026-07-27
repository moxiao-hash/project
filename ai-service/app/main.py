"""FastAPI 应用入口。

这个模块只负责组装 HTTP 应用。Agent 工作流、模型调用和 Java 客户端会放在各自
的模块中，避免应用入口随着功能增加而变成难以测试的大文件。
"""

import logging
from contextlib import asynccontextmanager
from datetime import UTC, datetime

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from fastapi import FastAPI

from app.api.conversations import router as conversations_router
from app.api.knowledge_conversations import router as knowledge_conversations_router
from app.api.model_status import router as model_status_router
from app.api.plan_adjustments import build_plan_adjustment_service
from app.api.plan_adjustments import router as plan_adjustments_router
from app.api.quiz_generation import router as quiz_generation_router
from app.api.task_conversations import router as task_conversations_router
from app.clients.java_backend import JavaBackendClient
from app.core.settings import get_settings
from app.material.analysis import DeepSeekMaterialAnalyzer, MaterialAnalyzer
from app.material.processing import MaterialProcessingService
from app.providers.model_factory import ModelConfigurationError, create_chat_model
from app.retrieval.factory import get_hybrid_index
from app.scheduler.nightly_adjustments import NightlyAdjustmentScheduler
from app.search.web_fetcher import SafeWebFetcher

logger = logging.getLogger(__name__)
_material_processing_service: MaterialProcessingService | None = None


async def run_nightly_adjustment_job() -> None:
    """运行一次可补偿的夜间分析；单个周期失败不会终止应用进程。"""

    settings = get_settings()
    try:
        service = build_plan_adjustment_service(settings)
        runner = NightlyAdjustmentScheduler(JavaBackendClient(settings), service)
        await runner.run_due(datetime.now(UTC))
    except ModelConfigurationError:
        logger.warning("未配置模型，跳过本轮夜间计划调整")
    except Exception:
        logger.exception("夜间计划调整任务执行失败")


async def run_material_processing_job() -> None:
    """领取至多一个持久化资料任务，避免一次轮询长时间占用事件循环。"""

    settings = get_settings()
    global _material_processing_service
    try:
        if _material_processing_service is None:
            cloud_analyzer = DeepSeekMaterialAnalyzer(create_chat_model(settings))
            _material_processing_service = MaterialProcessingService(
                JavaBackendClient(settings),
                MaterialAnalyzer(cloud_analyzer),
                worker_id=settings.material_worker_id,
                index=get_hybrid_index(settings.qdrant_path),
                web_fetcher=SafeWebFetcher(),
            )
        await _material_processing_service.process_once()
    except ModelConfigurationError:
        logger.warning("未配置模型，暂不处理普通资料")
    except Exception:
        logger.exception("资料处理任务执行失败")


@asynccontextmanager
async def lifespan(_: FastAPI):
    settings = get_settings()
    scheduler = AsyncIOScheduler(timezone="UTC")
    scheduler.add_job(
        run_nightly_adjustment_job,
        "interval",
        minutes=settings.nightly_adjustment_interval_minutes,
        id="nightly-plan-adjustments",
        coalesce=True,
        max_instances=1,
        replace_existing=True,
    )
    scheduler.add_job(
        run_material_processing_job,
        "interval",
        seconds=settings.material_processing_interval_seconds,
        id="material-processing",
        coalesce=True,
        max_instances=1,
        replace_existing=True,
    )
    scheduler.start()
    try:
        yield
    finally:
        scheduler.shutdown(wait=False)

app = FastAPI(
    title="StudyPilot AI Service",
    version="0.1.0",
    lifespan=lifespan,
)
app.include_router(model_status_router)
app.include_router(conversations_router)
app.include_router(task_conversations_router)
app.include_router(plan_adjustments_router)
app.include_router(knowledge_conversations_router)
app.include_router(quiz_generation_router)


@app.get("/health")
async def health() -> dict[str, str]:
    """返回进程级健康状态。

    健康检查不依赖模型 API Key 或 Java 后端，因此配置尚未完成时也能用于判断
    Python 服务本身是否已经成功启动。
    """

    return {"status": "UP", "service": "studypilot-ai"}
