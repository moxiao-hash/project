import asyncio
import base64
from pathlib import Path

import pytest
from fastapi import FastAPI, HTTPException
from pydantic import SecretStr
from starlette.requests import Request

from app import main as main_module
from app.api.conversations import get_conversation_service
from app.api.knowledge_conversations import get_knowledge_conversation_service
from app.api.task_conversations import get_task_conversation_service
from app.core.settings import Settings

pytestmark = pytest.mark.anyio


def request_for(application: FastAPI) -> Request:
    return Request({"type": "http", "app": application})


async def test_lifespan_constructs_one_shared_registry_of_each_kind(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    settings = Settings(
        _env_file=None,
        agent_state_db_path=str(tmp_path / "agent-state.sqlite3"),
        langgraph_aes_key=SecretStr(base64.b64encode(bytes(range(32))).decode()),
        agent_worker_count=1,
    )
    monkeypatch.setattr(main_module, "get_settings", lambda: settings)
    application = FastAPI()

    async with main_module.lifespan(application):
        persistence = application.state.agent_persistence
        plan = application.state.conversation_service
        task = application.state.task_conversation_service
        knowledge = application.state.knowledge_conversation_service

        assert plan._persistence is persistence
        assert task._persistence is persistence
        assert knowledge._persistence is persistence
        request = request_for(application)
        assert get_conversation_service(request, settings) is plan
        assert get_task_conversation_service(request, settings) is task
        assert get_knowledge_conversation_service(request, settings) is knowledge
        concurrent = await asyncio.gather(
            *[asyncio.to_thread(get_conversation_service, request, settings) for _ in range(20)]
        )
        assert all(item is plan for item in concurrent)

    with pytest.raises(HTTPException) as error:
        get_conversation_service(request_for(application), settings)
    assert error.value.status_code == 503


@pytest.mark.parametrize(
    "dependency",
    [
        get_conversation_service,
        get_task_conversation_service,
        get_knowledge_conversation_service,
    ],
)
async def test_registry_dependency_fails_before_lifespan_start(
    dependency,
) -> None:
    application = FastAPI()
    with pytest.raises(HTTPException) as error:
        dependency(request_for(application), Settings(_env_file=None))
    assert error.value.status_code == 503
    assert "尚未完成启动" in error.value.detail
