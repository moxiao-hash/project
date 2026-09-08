"""真实 Java → Python → Java/MySQL 冒烟；不冒充模型、浏览器或容器验收。

创建独立临时用户并只修改该用户的学习设置。账户保留用于追溯，不输出密码或 Token。
运行：ai-service/.venv/bin/python scripts/agent-native-smoke.py
"""

import json
import secrets
import uuid

import httpx


def main() -> None:
    with httpx.Client(base_url="http://127.0.0.1:8080", timeout=90) as client:
        def request(method: str, path: str, body=None, expected=200):
            response = client.request(method, path, json=body)
            # 错误响应可能包含用户信息，不整段输出。
            assert response.status_code == expected, f"{method} {path}: {response.status_code}"
            return response.json()

        assert request("GET", "/actuator/health")["status"] == "UP"
        user = request("POST", "/api/auth/register", {
            "email": f"task26-smoke-{uuid.uuid4()}@example.com",
            "password": secrets.token_urlsafe(24) + "Aa1!",
            "displayName": "Task26 独立验收",
        }, 201)
        client.headers["Authorization"] = "Bearer " + user["accessToken"]
        request("PUT", "/api/user-settings", {
            "timeZone": "Asia/Shanghai", "dailyStudyLimitMinutes": 60,
            "weekendPreference": "SAME", "defaultPrivacyLevel": "NORMAL",
            "weeklyAvailability": [],
        })
        conversation = request("POST", "/api/assistant/conversations", {"ownerId": "attacker"}, 201)
        assert "ownerId" not in conversation  # Java 公共门面移除内部身份字段。
        assert conversation["status"] == "READY"
        base = "/api/assistant/conversations/" + conversation["conversationId"]
        assert request("GET", base)["conversationId"] == conversation["conversationId"]
        before = request("GET", "/api/user-settings")
        target = 30 if before["dailyStudyLimitMinutes"] != 30 else 45
        message = {"message": f"把每天学习时间调整为 {target} 分钟", "idempotencyKey": str(uuid.uuid4())}
        preview = request("POST", base + "/messages", message)
        assert preview["status"] == "WAITING_CONFIRMATION"
        action = preview["pendingAction"]
        assert action["toolName"] == "settings.learning.update"
        assert action["riskLevel"] == "HIGH"
        assert request("POST", base + "/messages", message)["pendingAction"] == action
        chat_confirm = request("POST", base + "/messages", {
            "message": "确认", "idempotencyKey": str(uuid.uuid4()),
        })
        assert chat_confirm["status"] == "WAITING_CONFIRMATION"
        assert request("GET", "/api/user-settings") == before
        confirm_path = base + "/actions/" + action["actionId"] + "/confirm"
        completed = request("POST", confirm_path)
        assert completed["status"] == "COMPLETED"
        after = request("GET", "/api/user-settings")
        assert after["dailyStudyLimitMinutes"] == target
        audit = request("GET", "/api/audit-logs")
        executions = request("GET", "/api/agent-executions")
        execution = next(item for item in executions if item["id"] == action["executionId"])
        assert execution["status"] == "SUCCEEDED"
        assert {"EXECUTION_CREATED", "EXECUTION_CONFIRMED", "EXECUTION_STATUS_CHANGED"} <= {
            item["action"] for item in audit
        }
        assert request("POST", confirm_path) == completed
        assert request("GET", "/api/user-settings") == after
        assert request("GET", "/api/audit-logs") == audit
        assert request("GET", "/api/agent-executions") == executions
        events = client.get(base + "/events", headers={"Last-Event-ID": "0"})
        assert events.status_code == 200
        ids = [int(line[4:]) for line in events.text.splitlines() if line.startswith("id: ")]
        assert ids and ids == sorted(set(ids))
        cursor = ids[len(ids) // 2]
        replay = client.get(base + "/events", headers={"Last-Event-ID": str(cursor)})
        assert replay.status_code == 200
        replay_ids = [int(line[4:]) for line in replay.text.splitlines() if line.startswith("id: ")]
        assert replay_ids == [value for value in ids if value > cursor]
        assert request("GET", "/api/audit-logs") == audit
        print(json.dumps({
            "result": "PASS", "scope": "real Java/Python/MySQL; deterministic settings tool",
            "conversationId": conversation["conversationId"], "executionId": action["executionId"],
            "beforeMinutes": before["dailyStudyLimitMinutes"], "afterMinutes": target,
            "executionStatus": execution["status"], "events": ids, "replayedAfter": cursor,
            "replayEvents": replay_ids, "duplicateConfirmationNoSideEffects": True,
            "modelCalled": False, "runnerExecuted": False, "browserVerified": False,
        }, ensure_ascii=False))


if __name__ == "__main__":
    main()
