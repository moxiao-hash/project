"""Verify a running real-model turn can be cancelled without later completion."""

import asyncio
import json
import secrets
import uuid

import httpx
from dotenv import dotenv_values


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
                "email": f"task29-cancel-{uuid.uuid4()}@example.com",
                "password": secrets.token_urlsafe(24) + "Aa1!",
                "displayName": "Task29 cancel test",
            },
        )
        client.headers["Authorization"] = "Bearer " + account["accessToken"]
        await request("PUT", "/api/ai-settings/deepseek-key", {"apiKey": key})
        try:
            conversation = await request("POST", "/api/assistant/conversations", {})
            base = "/api/assistant/conversations/" + conversation["conversationId"]
            turn_id = str(uuid.uuid4())
            send_task = asyncio.create_task(
                request(
                    "POST",
                    base + "/messages",
                    {
                        "message": "详细讲解 Java 并发编程，写一篇约五千字的教程。",
                        "idempotencyKey": turn_id,
                        "clientContext": {},
                    },
                )
            )
            events = []
            cancel_response = None
            active_before_cancel = None
            async with client.stream(
                "GET",
                base + "/events",
                headers={"Last-Event-ID": str(conversation["lastEventSequence"])},
            ) as response:
                response.raise_for_status()
                async for line in response.aiter_lines():
                    if not line.startswith("data:"):
                        continue
                    event = json.loads(line[5:])
                    events.append(event)
                    if event["type"] == "TURN_STARTED" and cancel_response is None:
                        active_before_cancel = await request("GET", base)
                        cancel_response = await request(
                            "POST", base + f"/turns/{turn_id}/cancel"
                        )
                    if event["type"] in {
                        "TURN_CANCELLED",
                        "TURN_COMPLETED",
                        "TURN_FAILED",
                    }:
                        break
            result = await send_task
            await asyncio.sleep(1)
            snapshot = await request("GET", base)
            types = [event["type"] for event in events]
            assert (
                active_before_cancel and active_before_cancel["activeTurnId"] == turn_id
            )
            assert cancel_response and "取消" in cancel_response["reply"]
            assert types[-1] == "TURN_CANCELLED", types[-5:]
            assert "TURN_COMPLETED" not in types
            assert result["activeTurn"] is None and snapshot["activeTurn"] is None
            assert snapshot["messages"][-1]["status"] == "cancelled"
            print(
                json.dumps(
                    {
                        "result": "PASS",
                        "terminalEvent": types[-1],
                        "completionAfterCancel": False,
                        "conversationId": conversation["conversationId"],
                    }
                )
            )
        finally:
            await request("DELETE", "/api/ai-settings/deepseek-key")


if __name__ == "__main__":
    asyncio.run(main())
