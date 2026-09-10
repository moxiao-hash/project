"""Hold an active turn while the isolated Python process is restarted."""

import asyncio
import json
import os
import secrets
import signal
import uuid
from pathlib import Path

import httpx
from dotenv import dotenv_values

READY_FILE = Path("/tmp/task29-restart-ready.json")


async def main() -> None:
    key = dotenv_values("/Users/moxiao/IdeaProjects/project/ai-service/.env").get(
        "DEEPSEEK_API_KEY"
    )
    assert key, "Local model key missing"
    async with httpx.AsyncClient(
        base_url="http://127.0.0.1:8081", timeout=180
    ) as client:

        async def request(method: str, path: str, body: dict | None = None) -> dict:
            response = await client.request(method, path, json=body)
            response.raise_for_status()
            return response.json()

        account = await request(
            "POST",
            "/api/auth/register",
            {
                "email": f"task29-restart-{uuid.uuid4()}@example.com",
                "password": secrets.token_urlsafe(24) + "Aa1!",
                "displayName": "Task29 restart test",
            },
        )
        client.headers["Authorization"] = "Bearer " + account["accessToken"]
        await request("PUT", "/api/ai-settings/deepseek-key", {"apiKey": key})
        conversation = await request("POST", "/api/assistant/conversations", {})
        base = "/api/assistant/conversations/" + conversation["conversationId"]
        turn_id = str(uuid.uuid4())
        send_task = asyncio.create_task(
            request(
                "POST",
                base + "/messages",
                {
                    "message": "详细解释 Java 多线程与线程池，约五千字。",
                    "idempotencyKey": turn_id,
                    "clientContext": {},
                },
            )
        )
        before = None
        for _ in range(250):
            try:
                candidate = await request("GET", base)
                if candidate.get("activeTurnId") == turn_id:
                    before = candidate
                    break
            except (httpx.HTTPError, RuntimeError):
                pass
            await asyncio.sleep(0.02)
        assert before is not None, "No active turn observed before restart"
        READY_FILE.write_text(
            json.dumps(
                {
                    "conversationId": conversation["conversationId"],
                    "turnId": turn_id,
                    "cursor": before["lastEventSequence"],
                }
            ),
            encoding="utf-8",
        )
        target_pid = int(os.environ.get("TASK29_PYTHON_PID", "0"))
        if target_pid:
            os.kill(target_pid, signal.SIGKILL)
        try:
            try:
                await send_task
            except (httpx.HTTPError, RuntimeError):
                pass
            after = None
            for _ in range(600):
                try:
                    candidate = await request("GET", base)
                    if candidate.get("activeTurn") is None:
                        after = candidate
                        break
                except (httpx.HTTPError, RuntimeError):
                    pass
                await asyncio.sleep(0.1)
            assert after is not None, "Conversation was not restored after restart"
            assert after["activeTurnId"] is None
            assert after["lastEventSequence"] > before["lastEventSequence"]
            terminal = None
            async with client.stream(
                "GET",
                base + "/events",
                headers={"Last-Event-ID": str(before["lastEventSequence"])},
                timeout=15,
            ) as response:
                response.raise_for_status()
                async for line in response.aiter_lines():
                    if not line.startswith("data:"):
                        continue
                    event = json.loads(line[5:])
                    if event["type"] in {
                        "TURN_FAILED",
                        "TURN_COMPLETED",
                        "TURN_CANCELLED",
                    }:
                        terminal = event
                        break
            assert terminal is not None
            assert terminal["type"] == "TURN_FAILED", terminal
            assert terminal["payload"]["turnId"] == turn_id
            assert terminal["payload"]["reason"] == "SERVICE_RESTARTED"
            print(
                json.dumps(
                    {
                        "result": "PASS",
                        "reason": "SERVICE_RESTARTED",
                        "cursorAdvanced": True,
                        "conversationId": conversation["conversationId"],
                    }
                )
            )
        finally:
            READY_FILE.unlink(missing_ok=True)
            await request("DELETE", "/api/ai-settings/deepseek-key")


if __name__ == "__main__":
    asyncio.run(main())
