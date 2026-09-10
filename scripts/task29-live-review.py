"""Real local Java/Python/MySQL SSE check; uses one isolated test account."""

import asyncio
import json
import secrets
import time
import uuid

import httpx


async def main():
    async with httpx.AsyncClient(
        base_url="http://127.0.0.1:8081", timeout=120
    ) as client:

        async def request(method, path, body=None):
            response = await client.request(method, path, json=body)
            response.raise_for_status()
            return response.json()

        account = await request(
            "POST",
            "/api/auth/register",
            {
                "email": f"task29-live-{uuid.uuid4()}@example.com",
                "password": secrets.token_urlsafe(24) + "Aa1!",
                "displayName": "Task29 isolated verification",
            },
        )
        client.headers["Authorization"] = "Bearer " + account["accessToken"]
        conv = await request("POST", "/api/assistant/conversations", {})
        assert {"activeTurn", "activeTurnId", "lastEventSequence"} <= conv.keys()
        base = "/api/assistant/conversations/" + conv["conversationId"]
        events = []
        heartbeat = asyncio.Event()
        start = time.monotonic()

        async def stream(cursor, received, stop_on_heartbeat=False):
            async with client.stream(
                "GET",
                base + "/events",
                headers={
                    "Last-Event-ID": str(cursor),
                },
                timeout=45,
            ) as response:
                response.raise_for_status()
                async for line in response.aiter_lines():
                    if line.startswith("data:"):
                        received.append(json.loads(line[5:].strip()))
                    elif line.startswith(":") and "heartbeat" in line:
                        heartbeat.set()
                        if stop_on_heartbeat:
                            return

        listener = asyncio.create_task(stream(conv["lastEventSequence"], events, True))
        await asyncio.sleep(0.3)
        turn = str(uuid.uuid4())
        result = await request(
            "POST",
            base + "/messages",
            {
                "message": "打开错题集",
                "idempotencyKey": turn,
                "clientContext": {},
            },
        )
        await asyncio.wait_for(listener, 40)
        sequences = [e["sequence"] for e in events]
        assert sequences == sorted(set(sequences)) and len(sequences) > 2
        assert any(e["type"] == "TURN_STARTED" for e in events)
        assert events[-1]["type"] == "TURN_COMPLETED"
        assert result["activeTurn"] is None
        assert result["lastEventSequence"] == sequences[-1]
        assert 25 <= time.monotonic() - start <= 42
        cursor = sequences[len(sequences) // 2]
        replay = []
        reader = asyncio.create_task(stream(cursor, replay))
        for _ in range(50):
            if replay and replay[-1]["sequence"] == sequences[-1]:
                break
            await asyncio.sleep(0.1)
        reader.cancel()
        try:
            await reader
        except asyncio.CancelledError:
            pass
        assert [e["sequence"] for e in replay] == [s for s in sequences if s > cursor]
        before = await request("GET", base)
        await request(
            "POST",
            base + "/messages",
            {
                "message": "打开错题集",
                "idempotencyKey": turn,
                "clientContext": {},
            },
        )
        after = await request("GET", base)
        assert before == after, "duplicate turn must not append events/messages"
        print(
            json.dumps(
                {
                    "result": "PASS",
                    "conversationId": conv["conversationId"],
                    "sequences": sequences,
                    "replayedAfter": cursor,
                    "heartbeatSeconds": round(time.monotonic() - start, 1),
                    "duplicateTurnNoMutation": True,
                    "modelTested": False,
                    "browserTested": False,
                }
            )
        )


if __name__ == "__main__":
    asyncio.run(main())
