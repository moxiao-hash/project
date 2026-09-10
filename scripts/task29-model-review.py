"""Real model delta and snapshot-resume probe; never prints credentials."""

import asyncio
import json
import secrets
import uuid

import httpx
from dotenv import dotenv_values


async def main():
    key = dotenv_values("/Users/moxiao/IdeaProjects/project/ai-service/.env").get(
        "DEEPSEEK_API_KEY"
    )
    assert key, "Local model key missing"
    async with httpx.AsyncClient(
        base_url="http://127.0.0.1:8081", timeout=180
    ) as client:

        async def request(method, path, body=None):
            response = await client.request(method, path, json=body)
            response.raise_for_status()
            return response.json()

        account = await request(
            "POST",
            "/api/auth/register",
            {
                "email": f"task29-model-{uuid.uuid4()}@example.com",
                "password": secrets.token_urlsafe(24) + "Aa1!",
                "displayName": "Task29 model test",
            },
        )
        client.headers["Authorization"] = "Bearer " + account["accessToken"]
        await request("PUT", "/api/ai-settings/deepseek-key", {"apiKey": key})
        try:
            conv = await request("POST", "/api/assistant/conversations", {})
            base = "/api/assistant/conversations/" + conv["conversationId"]
            turn = str(uuid.uuid4())
            task = asyncio.create_task(
                request(
                    "POST",
                    base + "/messages",
                    {
                        "message": "解释 Java 的类和对象，请用多个例子详细说明，约1500字。不要联网搜索。",
                        "idempotencyKey": turn,
                        "clientContext": {},
                    },
                )
            )
            refreshed = None
            async with client.stream(
                "GET",
                base + "/events",
                headers={"Last-Event-ID": str(conv["lastEventSequence"])},
            ) as response:
                response.raise_for_status()
                async for line in response.aiter_lines():
                    if not line.startswith("data:"):
                        continue
                    event = json.loads(line[5:])
                    if event["type"] == "ASSISTANT_DELTA":
                        refreshed = await request("GET", base)
                        if (
                            refreshed["activeTurn"]
                            and refreshed["activeTurn"]["assistantText"]
                        ):
                            break
                    if event["type"] in ("TURN_FAILED", "TURN_COMPLETED"):
                        break
            assert refreshed and refreshed["activeTurn"], (
                "No running model prefix observed"
            )
            prefix = refreshed["activeTurn"]["assistantText"]
            suffix = ""
            resumed_ids = []
            final_reply = None
            async with client.stream(
                "GET",
                base + "/events",
                headers={"Last-Event-ID": str(refreshed["lastEventSequence"])},
            ) as response:
                response.raise_for_status()
                async for line in response.aiter_lines():
                    if not line.startswith("data:"):
                        continue
                    event = json.loads(line[5:])
                    resumed_ids.append(event["sequence"])
                    if event["type"] == "ASSISTANT_DELTA":
                        suffix += event["payload"]["delta"]
                    if event["type"] == "TURN_COMPLETED":
                        final_reply = event["payload"]["reply"]
                        break
                    assert event["type"] != "TURN_FAILED", "Model turn failed"
            result = await task
            assert prefix + suffix == final_reply == result["reply"]
            assert resumed_ids == sorted(set(resumed_ids))
            assert min(resumed_ids) > refreshed["lastEventSequence"]
            assert result["activeTurn"] is None
            print(
                json.dumps(
                    {
                        "result": "PASS",
                        "model": result["modelName"],
                        "prefixChars": len(prefix),
                        "suffixChars": len(suffix),
                        "resumedEvents": len(resumed_ids),
                        "exactAnswerRecovery": True,
                        "conversationId": conv["conversationId"],
                        "browserTested": False,
                    }
                )
            )
        finally:
            await request("DELETE", "/api/ai-settings/deepseek-key")


if __name__ == "__main__":
    asyncio.run(main())
