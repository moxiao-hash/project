"""FastAPI 应用入口。

这个模块只负责组装 HTTP 应用。Agent 工作流、模型调用和 Java 客户端会放在各自
的模块中，避免应用入口随着功能增加而变成难以测试的大文件。
"""

from fastapi import FastAPI

from app.api.conversations import router as conversations_router
from app.api.model_status import router as model_status_router
from app.api.task_conversations import router as task_conversations_router

app = FastAPI(
    title="StudyPilot AI Service",
    version="0.1.0",
)
app.include_router(model_status_router)
app.include_router(conversations_router)
app.include_router(task_conversations_router)


@app.get("/health")
async def health() -> dict[str, str]:
    """返回进程级健康状态。

    健康检查不依赖模型 API Key 或 Java 后端，因此配置尚未完成时也能用于判断
    Python 服务本身是否已经成功启动。
    """

    return {"status": "UP", "service": "studypilot-ai"}
