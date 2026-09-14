#!/usr/bin/env python3
"""Task 30 整改真实三端探针：五类界面动作、稳定 actionId 与回执语义。

本脚本只访问真实运行中的 Java 公开门面（默认 ``http://localhost:8080``），
不 mock 任何服务，因此可以作为 ``[REAL_E2E]`` 证据运行：

* 每个受控场景发送真实用户消息，读取真实 SSE 事件流；
* 校验 ``UI_ACTION`` 的类型、``routeKey``、参数与稳定 ``actionId``；
* 校验 REST 会话快照携带同一个 ``actionId``；
* 校验回执 200/幂等 200/冲突 409/他人 404，以及跨用户会话隔离。

前置条件：
    STUDY_PILOT_BASE_URL    Java 后端基地址（默认 http://localhost:8080）
    STUDY_PILOT_TOKEN_A     用户 A 的 Bearer 令牌（必填）
    STUDY_PILOT_TOKEN_B     用户 B 的 Bearer 令牌（可选，用于隔离校验）

用法：
    PYTHONPATH=ai-service python ai-service/scripts/task30-remediation-probe.py \
        --base-url http://localhost:8080
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from dataclasses import dataclass

import httpx


@dataclass(frozen=True)
class Scenario:
    name: str
    message: str
    action_type: str
    route_key: str
    params: dict[str, str]


SCENARIOS: tuple[Scenario, ...] = (
    Scenario("open_modal_goal", "打开新建目标弹窗", "OPEN_MODAL", "LEARNING_GOALS", {"modalKey": "CREATE_GOAL"}),
    Scenario("open_modal_plan", "打开新建计划弹窗", "OPEN_MODAL", "LEARNING_PLANS", {"modalKey": "CREATE_PLAN"}),
    Scenario("open_modal_material", "打开资料导入面板", "OPEN_MODAL", "MATERIALS", {"modalKey": "IMPORT_MATERIAL"}),
    Scenario("prefill_goal", "预填目标：学习 Java 基础", "PREFILL_FORM", "LEARNING_GOALS", {"formKey": "GOAL_FORM", "title": "学习 Java 基础"}),
    Scenario("prefill_plan", "预填计划：第一阶段基础", "PREFILL_FORM", "LEARNING_PLANS", {"formKey": "PLAN_FORM", "title": "第一阶段基础"}),
    Scenario("prefill_material", "预填资料：Java 学习笔记", "PREFILL_FORM", "MATERIALS", {"formKey": "MATERIAL_FORM", "title": "Java 学习笔记"}),
    Scenario("refresh_roadmap", "刷新学习路线", "REFRESH_RESOURCE", "ROADMAP", {"resourceKey": "ROADMAP"}),
    Scenario("refresh_today", "刷新今日任务", "REFRESH_RESOURCE", "TODAY", {"resourceKey": "TODAY_TASKS"}),
    Scenario("refresh_goals", "刷新学习目标", "REFRESH_RESOURCE", "LEARNING_GOALS", {"resourceKey": "LEARNING_GOALS"}),
    Scenario("refresh_plans", "刷新学习计划", "REFRESH_RESOURCE", "LEARNING_PLANS", {"resourceKey": "LEARNING_PLANS"}),
    Scenario("refresh_notifications", "刷新通知", "REFRESH_RESOURCE", "NOTIFICATIONS", {"resourceKey": "NOTIFICATIONS"}),
    Scenario("refresh_wrong_questions", "刷新错题集", "REFRESH_RESOURCE", "WRONG_QUESTIONS", {"resourceKey": "WRONG_QUESTIONS"}),
    Scenario("refresh_mastery", "刷新掌握度", "REFRESH_RESOURCE", "MASTERY", {"resourceKey": "MASTERY"}),
    Scenario("refresh_activity", "刷新执行记录", "REFRESH_RESOURCE", "AGENT_ACTIVITY", {"resourceKey": "ACTIVITY"}),
    Scenario("focus_message_input", "聚焦消息输入框", "FOCUS_ELEMENT", "ASSISTANT", {"elementKey": "MESSAGE_INPUT"}),
    Scenario("focus_plan_title", "聚焦计划标题", "FOCUS_ELEMENT", "LEARNING_PLANS", {"elementKey": "PLAN_TITLE_INPUT"}),
)


class ProbeFailure(AssertionError):
    """真实链路与冻结契约不一致。"""


def _headers(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}


def _parse_sse(frames: list[str]) -> list[dict]:
    events: list[dict] = []
    for frame in frames:
        name = "message"
        data_lines: list[str] = []
        for line in frame.splitlines():
            if line.startswith("event:"):
                name = line[len("event:"):].strip()
            elif line.startswith("data:"):
                data_lines.append(line[len("data:"):].strip())
        if not data_lines:
            continue
        raw = "\n".join(data_lines)
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError:
            continue
        if isinstance(payload, dict):
            payload.setdefault("_sseEvent", name)
            events.append(payload)
    return events


def _read_events(
    client: httpx.Client,
    base_url: str,
    conversation_id: str,
    token: str,
    *,
    timeout_seconds: float = 30.0,
) -> list[dict]:
    frames: list[str] = []
    buffer: list[str] = []
    deadline = time.monotonic() + timeout_seconds
    with client.stream(
        "GET",
        f"{base_url}/api/assistant/conversations/{conversation_id}/events",
        headers=_headers(token),
        timeout=timeout_seconds,
    ) as response:
        response.raise_for_status()
        for line in response.iter_lines():
            if time.monotonic() > deadline:
                raise ProbeFailure("SSE 读取超时")
            if line == "":
                if buffer:
                    frames.append("\n".join(buffer))
                    buffer = []
                if any("TURN_COMPLETED" in frame for frame in frames[-3:]):
                    break
                continue
            buffer.append(line)
        if buffer:
            frames.append("\n".join(buffer))
    events = _parse_sse(frames)
    if not events:
        raise ProbeFailure("SSE 未收到任何事件")
    return events


def _assert(condition: bool, message: str) -> None:
    if not condition:
        raise ProbeFailure(message)


def run_scenario(
    client: httpx.Client,
    base_url: str,
    token_a: str,
    scenario: Scenario,
) -> None:
    created = client.post(
        f"{base_url}/api/assistant/conversations", headers=_headers(token_a), json={}
    )
    created.raise_for_status()
    conversation_id = created.json()["conversationId"]

    sent = client.post(
        f"{base_url}/api/assistant/conversations/{conversation_id}/messages",
        headers=_headers(token_a),
        json={
            "message": scenario.message,
            "idempotencyKey": f"probe:{scenario.name}:{conversation_id}",
            "clientContext": {"routeName": "assistant", "routeParams": {}},
        },
    )
    sent.raise_for_status()

    events = _read_events(client, base_url, conversation_id, token_a)
    ui_actions = [event for event in events if event.get("type") == "UI_ACTION"]
    _assert(ui_actions, f"{scenario.name}: SSE 未下发 UI_ACTION")
    payload = ui_actions[-1].get("payload", {})
    _assert(payload.get("type") == scenario.action_type, f"{scenario.name}: 类型 {payload.get('type')}")
    _assert(payload.get("routeKey") == scenario.route_key, f"{scenario.name}: routeKey 不匹配")
    _assert(payload.get("params") == scenario.params, f"{scenario.name}: 参数不匹配 {payload.get('params')}")
    action_id = payload.get("actionId")
    _assert(isinstance(action_id, str) and action_id, f"{scenario.name}: SSE 缺少稳定 actionId")

    snapshot = client.get(
        f"{base_url}/api/assistant/conversations/{conversation_id}", headers=_headers(token_a)
    )
    snapshot.raise_for_status()
    ui_actions_rest = snapshot.json().get("uiActions") or []
    _assert(ui_actions_rest, f"{scenario.name}: REST 快照缺少 uiActions")
    _assert(
        ui_actions_rest[-1].get("actionId") == action_id,
        f"{scenario.name}: REST 与 SSE 的 actionId 不一致",
    )

    receipt_body = {
        "actionId": action_id,
        "status": "SUCCEEDED",
        "currentRoute": "assistant",
    }
    first = client.post(
        f"{base_url}/api/assistant/conversations/{conversation_id}/actions/receipt",
        headers=_headers(token_a),
        json=receipt_body,
    )
    _assert(first.status_code == 200, f"{scenario.name}: 首次回执应为 200, got {first.status_code}")
    replay = client.post(
        f"{base_url}/api/assistant/conversations/{conversation_id}/actions/receipt",
        headers=_headers(token_a),
        json=receipt_body,
    )
    _assert(replay.status_code == 200, f"{scenario.name}: 幂等回执应为 200")
    _assert(replay.json() == first.json(), f"{scenario.name}: 幂等回执响应必须一致")
    conflict = client.post(
        f"{base_url}/api/assistant/conversations/{conversation_id}/actions/receipt",
        headers=_headers(token_a),
        json={**receipt_body, "status": "FAILED"},
    )
    _assert(conflict.status_code == 409, f"{scenario.name}: 冲突回执应为 409, got {conflict.status_code}")


def run_cross_owner(client: httpx.Client, base_url: str, token_a: str, token_b: str) -> None:
    created = client.post(
        f"{base_url}/api/assistant/conversations", headers=_headers(token_a), json={}
    )
    created.raise_for_status()
    conversation_id = created.json()["conversationId"]
    foreign = client.get(
        f"{base_url}/api/assistant/conversations/{conversation_id}", headers=_headers(token_b)
    )
    _assert(foreign.status_code in (403, 404), f"他人会话应不可见, got {foreign.status_code}")
    receipt = client.post(
        f"{base_url}/api/assistant/conversations/{conversation_id}/actions/receipt",
        headers=_headers(token_b),
        json={"actionId": "00000000-0000-0000-0000-000000000000", "status": "SUCCEEDED", "currentRoute": "assistant"},
    )
    _assert(receipt.status_code in (403, 404), f"他人回执应被拒绝, got {receipt.status_code}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--base-url",
        default=os.environ.get("STUDY_PILOT_BASE_URL", "http://localhost:8080"),
    )
    parser.add_argument("--token-a", default=os.environ.get("STUDY_PILOT_TOKEN_A"))
    parser.add_argument("--token-b", default=os.environ.get("STUDY_PILOT_TOKEN_B"))
    parser.add_argument("--scenario", action="append", default=None)
    args = parser.parse_args(argv)

    if not args.token_a:
        print("缺少 STUDY_PILOT_TOKEN_A；本探针只对真实登录用户运行，不生成假回执。", file=sys.stderr)
        return 2

    selected = [
        scenario
        for scenario in SCENARIOS
        if args.scenario is None or scenario.name in set(args.scenario)
    ]
    failures: list[str] = []
    with httpx.Client(timeout=30.0) as client:
        for scenario in selected:
            try:
                run_scenario(client, args.base_url, args.token_a, scenario)
                print(f"[PASS] {scenario.name}: {scenario.message}")
            except (ProbeFailure, httpx.HTTPStatusError) as exc:
                failures.append(f"{scenario.name}: {exc}")
                print(f"[FAIL] {scenario.name}: {exc}", file=sys.stderr)
        if args.token_b:
            try:
                run_cross_owner(client, args.base_url, args.token_a, args.token_b)
                print("[PASS] cross_owner_isolation")
            except (ProbeFailure, httpx.HTTPStatusError) as exc:
                failures.append(f"cross_owner_isolation: {exc}")
                print(f"[FAIL] cross_owner_isolation: {exc}", file=sys.stderr)

    if failures:
        print(f"\n{len(failures)} 项失败：", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        return 1
    print("\n全部受控场景通过真实 Java 公开门面验证。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
