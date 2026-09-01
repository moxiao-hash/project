"""FastAPI 应用入口。

这个模块只负责组装 HTTP 应用。Agent 工作流、模型调用和 Java 客户端会放在各自
的模块中，避免应用入口随着功能增加而变成难以测试的大文件。
"""

import logging
from contextlib import asynccontextmanager
from datetime import UTC, datetime

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, Response
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest

from app.api.conversations import (
    OwnerScopedConversationServices,
)
from app.api.conversations import (
    router as conversations_router,
)
from app.api.knowledge_conversations import (
    OwnerScopedKnowledgeServices,
)
from app.api.knowledge_conversations import (
    router as knowledge_conversations_router,
)
from app.api.model_status import router as model_status_router
from app.api.plan_adjustments import build_plan_adjustment_service
from app.api.plan_adjustments import router as plan_adjustments_router
from app.api.quiz_generation import router as quiz_generation_router
from app.api.task_conversations import (
    OwnerScopedTaskConversationServices,
)
from app.api.task_conversations import (
    router as task_conversations_router,
)
from app.api.teaching_conversations import OwnerScopedTeachingServices
from app.api.teaching_conversations import router as teaching_conversations_router
from app.assessment.evaluation import CodingEvaluationWorker, DeepSeekCodingEvaluator
from app.assessment.generator import DeepSeekQuizGenerator
from app.assessment.service import (
    RoadmapDiagnosticWorker,
    RoadmapGraduationWorker,
    RoadmapQuizWorker,
)
from app.clients.java_backend import JavaBackendClient
from app.core.request_context import (
    REQUEST_ID_HEADER,
    bind_request_id,
    current_request_id,
    reset_request_id,
)
from app.core.settings import get_settings
from app.material.analysis import DeepSeekMaterialAnalyzer, MaterialAnalyzer
from app.material.processing import MaterialProcessingService
from app.observability.safe_logging import install_secret_redaction
from app.persistence.lifecycle import open_agent_persistence
from app.providers.credentials import CredentialProvider, CredentialResolver
from app.providers.model_factory import ModelConfigurationError, create_chat_model
from app.retrieval.factory import get_hybrid_index
from app.scheduler.nightly_adjustments import NightlyAdjustmentScheduler
from app.search.service import WebSearchService
from app.search.tavily import TavilySearchClient
from app.search.web_fetcher import SafeWebFetcher

logger = logging.getLogger(__name__)
install_secret_redaction()
_material_processing_service: MaterialProcessingService | None = None
_coding_evaluation_worker: CodingEvaluationWorker | None = None
_roadmap_quiz_worker: RoadmapQuizWorker | None = None
_roadmap_diagnostic_worker: RoadmapDiagnosticWorker | None = None
_roadmap_graduation_worker: RoadmapGraduationWorker | None = None


async def build_owner_material_analyzer(
    owner_id: str,
    settings,
    java: JavaBackendClient,
) -> MaterialAnalyzer:
    key = await CredentialResolver(java, settings).resolve(owner_id, CredentialProvider.DEEPSEEK)
    return MaterialAnalyzer(DeepSeekMaterialAnalyzer(create_chat_model(settings, key)))


async def build_owner_coding_evaluator(
    owner_id: str,
    settings,
    java: JavaBackendClient,
) -> DeepSeekCodingEvaluator:
    key = await CredentialResolver(java, settings).resolve(owner_id, CredentialProvider.DEEPSEEK)
    return DeepSeekCodingEvaluator(create_chat_model(settings, key))


async def build_owner_adjustment_service(
    owner_id: str,
    settings,
    java: JavaBackendClient,
):
    key = await CredentialResolver(java, settings).resolve(owner_id, CredentialProvider.DEEPSEEK)
    return build_plan_adjustment_service(settings, key)


async def run_nightly_adjustment_job() -> None:
    """运行一次可补偿的夜间分析；单个周期失败不会终止应用进程。"""

    settings = get_settings()
    try:
        java = JavaBackendClient(settings)

        async def service_for(owner_id: str):
            return await build_owner_adjustment_service(owner_id, settings, java)

        runner = NightlyAdjustmentScheduler(
            java,
            None,
            adjustment_service_factory=service_for,
        )
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
            java = JavaBackendClient(settings)

            async def analyzer_for(owner_id: str):
                return await build_owner_material_analyzer(owner_id, settings, java)

            _material_processing_service = MaterialProcessingService(
                java,
                None,
                analyzer_factory=analyzer_for,
                worker_id=settings.material_worker_id,
                index=get_hybrid_index(
                    settings.qdrant_path,
                    settings.fastembed_cache_path,
                ),
                web_fetcher=SafeWebFetcher(),
            )
        await _material_processing_service.process_once()
    except ModelConfigurationError:
        logger.warning("未配置模型，暂不处理普通资料")
    except Exception:
        logger.exception("资料处理任务执行失败")


async def run_coding_evaluation_job() -> None:
    """异步评估一批代码文本；用户代码不会在本服务中执行。"""

    settings = get_settings()
    global _coding_evaluation_worker
    try:
        if _coding_evaluation_worker is None:
            java = JavaBackendClient(settings)

            async def evaluator_for(owner_id: str):
                return await build_owner_coding_evaluator(owner_id, settings, java)

            _coding_evaluation_worker = CodingEvaluationWorker(
                java,
                None,
                evaluator_factory=evaluator_for,
                worker_id=settings.coding_evaluation_worker_id,
            )
        await _coding_evaluation_worker.process_once()
    except ModelConfigurationError:
        logger.warning("未配置模型，暂不评估代码文本")
    except Exception:
        logger.exception("代码文本评估任务执行失败")


async def run_roadmap_quiz_job() -> None:
    """领取至多一个持久化路线节点测验任务。"""

    settings = get_settings()
    global _roadmap_quiz_worker, _roadmap_diagnostic_worker, _roadmap_graduation_worker
    try:
        if _roadmap_quiz_worker is None:
            java = JavaBackendClient(settings)
            resolver = CredentialResolver(java, settings)

            async def generator_for(owner_id: str):
                key = await resolver.resolve(owner_id, CredentialProvider.DEEPSEEK)
                return DeepSeekQuizGenerator(create_chat_model(settings, key))

            async def web_search_for(owner_id: str):
                key = await resolver.resolve(owner_id, CredentialProvider.TAVILY)
                return WebSearchService(
                    TavilySearchClient(key, base_url=settings.tavily_base_url),
                    java,
                )

            _roadmap_quiz_worker = RoadmapQuizWorker(
                java,
                None,
                None,
                generator_factory=generator_for,
                web_search_factory=web_search_for,
                worker_id=settings.roadmap_quiz_worker_id,
                model_name=settings.model_name,
            )
        await _roadmap_quiz_worker.process_once()
        if _roadmap_diagnostic_worker is None:
            diagnostic_java = JavaBackendClient(settings)
            diagnostic_resolver = CredentialResolver(diagnostic_java, settings)

            async def diagnostic_generator_for(owner_id: str):
                key = await diagnostic_resolver.resolve(
                    owner_id, CredentialProvider.DEEPSEEK
                )
                return DeepSeekQuizGenerator(create_chat_model(settings, key))

            _roadmap_diagnostic_worker = RoadmapDiagnosticWorker(
                diagnostic_java,
                None,
                generator_factory=diagnostic_generator_for,
                worker_id=settings.roadmap_quiz_worker_id + "-diagnostic",
                model_name=settings.model_name,
            )
        await _roadmap_diagnostic_worker.process_once()
        if _roadmap_graduation_worker is None:
            graduation_java = JavaBackendClient(settings)
            graduation_resolver = CredentialResolver(graduation_java, settings)

            async def graduation_generator_for(owner_id: str):
                key = await graduation_resolver.resolve(
                    owner_id, CredentialProvider.DEEPSEEK
                )
                return DeepSeekQuizGenerator(create_chat_model(settings, key))

            _roadmap_graduation_worker = RoadmapGraduationWorker(
                graduation_java,
                None,
                generator_factory=graduation_generator_for,
                worker_id=settings.roadmap_quiz_worker_id + "-graduation",
                model_name=settings.model_name,
            )
        await _roadmap_graduation_worker.process_once()
    except ModelConfigurationError:
        logger.warning("未配置模型，暂不生成路线节点测验")
    except Exception:
        logger.exception("路线节点测验生成任务执行失败")


@asynccontextmanager
async def lifespan(application: FastAPI):
    global _roadmap_quiz_worker, _roadmap_diagnostic_worker, _roadmap_graduation_worker
    # Uvicorn 可能在导入 app 后重建 handler；启动阶段再次幂等安装，确保生产日志
    # 也经过敏感字段脱敏。
    install_secret_redaction()
    settings = get_settings()
    persistence = await open_agent_persistence(settings)
    scheduler = None
    scheduler_started = False
    original_error: BaseException | None = None
    try:
        application.state.agent_persistence = persistence
        # 三类 registry 必须在单线程启动阶段一次性创建。同步 FastAPI 依赖只读取这些
        # 实例，避免多个首请求在线程池中同时构造出不同 registry 和不同会话锁。
        application.state.conversation_service = OwnerScopedConversationServices(
            settings,
            persistence=persistence,
        )
        application.state.task_conversation_service = OwnerScopedTaskConversationServices(
            settings,
            persistence=persistence,
        )
        application.state.knowledge_conversation_service = OwnerScopedKnowledgeServices(
            settings,
            persistence=persistence,
        )
        application.state.teaching_conversation_service = OwnerScopedTeachingServices(
            settings,
            persistence=persistence,
        )
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
        scheduler.add_job(
            run_coding_evaluation_job,
            "interval",
            seconds=settings.coding_evaluation_interval_seconds,
            id="coding-evaluation",
            coalesce=True,
            max_instances=1,
            replace_existing=True,
        )
        scheduler.add_job(
            run_roadmap_quiz_job,
            "interval",
            seconds=settings.roadmap_quiz_interval_seconds,
            id="roadmap-quiz-generation",
            coalesce=True,
            max_instances=1,
            replace_existing=True,
        )
        scheduler.start()
        scheduler_started = True
        yield
    except BaseException as exc:
        original_error = exc
        raise
    finally:
        _roadmap_quiz_worker = None
        _roadmap_diagnostic_worker = None
        _roadmap_graduation_worker = None
        cleanup_errors: list[Exception] = []
        if scheduler_started and scheduler is not None:
            try:
                scheduler.shutdown(wait=False)
            except Exception as exc:
                cleanup_errors.append(exc)
        for state_name in (
            "conversation_service",
            "task_conversation_service",
            "knowledge_conversation_service",
            "teaching_conversation_service",
            "agent_persistence",
        ):
            if hasattr(application.state, state_name):
                delattr(application.state, state_name)
        try:
            await persistence.close()
        except Exception as exc:
            cleanup_errors.append(exc)
        if cleanup_errors:
            if original_error is not None:
                for cleanup_error in cleanup_errors:
                    logger.error(
                        "FastAPI 启动或运行失败后的资源清理也发生异常",
                        exc_info=(
                            type(cleanup_error),
                            cleanup_error,
                            cleanup_error.__traceback__,
                        ),
                    )
            else:
                raise ExceptionGroup("FastAPI 生命周期资源清理失败", cleanup_errors)


app = FastAPI(
    title="StudyPilot AI Service",
    version="0.1.0",
    lifespan=lifespan,
)


@app.middleware("http")
async def request_correlation(request: Request, call_next):
    """贯穿 FastAPI 与所有 httpx 下游请求的关联标识。"""

    token = bind_request_id(request.headers.get(REQUEST_ID_HEADER))
    try:
        request_id = current_request_id() or ""
        try:
            response = await call_next(request)
        except Exception:
            # 不记录 query/body/header，只保留安全路径与关联 ID；日志过滤器负责
            # 对异常 traceback 中意外出现的凭据做最后一道脱敏。
            logger.exception(
                "http.request.failed requestId=%s method=%s path=%s",
                request_id,
                request.method,
                request.url.path,
            )
            response = JSONResponse(
                status_code=500,
                content={
                    "code": "INTERNAL_ERROR",
                    "message": "服务内部错误",
                },
            )
        response.headers[REQUEST_ID_HEADER] = request_id
        return response
    finally:
        reset_request_id(token)


app.include_router(model_status_router)
app.include_router(conversations_router)
app.include_router(task_conversations_router)
app.include_router(plan_adjustments_router)
app.include_router(knowledge_conversations_router)
app.include_router(teaching_conversations_router)
app.include_router(quiz_generation_router)


@app.get("/metrics", include_in_schema=False)
async def metrics() -> Response:
    """Prometheus 抓取端点；不包含 owner、请求 ID、正文或凭据标签。"""

    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)


@app.get("/health")
async def health() -> dict[str, str]:
    """返回进程级健康状态。

    健康检查不依赖模型 API Key 或 Java 后端，因此配置尚未完成时也能用于判断
    Python 服务本身是否已经成功启动。
    """

    return {"status": "UP", "service": "studypilot-ai"}
