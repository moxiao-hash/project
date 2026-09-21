"""Task 32 一次性临时仓库 REAL_E2E：补丁预览 → 确认 → 容器测试 → diff → commit 确认 → push 确认。

设计约束（与 Task 32 冻结范围一致）：

- 只在一次性临时目录里创建**新建的**工作仓库与本地 bare remote，绝不把 StudyPilot 主仓库
  当作破坏性样本，也不读写任何既有仓库。
- 通过真实运行中的 Java 服务 + 真实 MySQL 驱动治理链路（internal agent-tools + 专用确认 +
  AgentExecution/通知/审计），不使用 H2、MockMvc 或桩替身。
- 容器测试环节没有可用容器运行时（Docker/Colima 未运行、Podman VM 未启动）时明确记为
  BLOCKED，**不降级为宿主 shell 执行**，也不把这一步写成通过。
- 不输出密码、Token、internal token 或任何凭据；只输出结构化步骤结论。

运行：
    INTERNAL_SERVICE_TOKEN=<本次运行的服务令牌> \
    /Users/moxiao/IdeaProjects/project/ai-service/.venv/bin/python scripts/task32-temp-git-e2e.py
"""

from __future__ import annotations

import json
import os
import secrets
import shutil
import subprocess
import sys
import tempfile
import uuid
from pathlib import Path

import httpx

BASE_URL = os.environ.get("STUDYPILOT_JAVA_BASE_URL", "http://127.0.0.1:8080")
INTERNAL_TOKEN = os.environ.get("INTERNAL_SERVICE_TOKEN", "")

STEPS: list[dict[str, object]] = []


def record(step: str, outcome: str, detail: str) -> None:
    STEPS.append({"step": step, "outcome": outcome, "detail": detail})


def run(directory: Path, *command: str) -> str:
    process = subprocess.run(
        list(command), cwd=directory, capture_output=True, text=True, check=False
    )
    if process.returncode != 0:
        raise RuntimeError(f"{' '.join(command)} 失败: {process.stderr.strip()[:400]}")
    return process.stdout.strip()


def git(directory: Path, *arguments: str) -> str:
    return run(directory, "git", *arguments)


def build_repository(root: Path) -> tuple[Path, Path]:
    """新建一次性工作仓库与本地 bare remote，并建立可提交、可推送的最小项目结构。"""

    workspace = root / "workspace"
    remote = root / "remote.git"
    workspace.mkdir()
    git(workspace, "init", "-b", "main")
    git(workspace, "config", "user.name", "StudyPilot Task32")
    git(workspace, "config", "user.email", "task32@example.com")
    run(root, "git", "init", "--bare", str(remote))
    git(workspace, "remote", "add", "origin", remote.as_uri())

    # 嵌套子项目：用于验证 Runner 在 backend/ 子目录而不是工作区根目录执行。
    (workspace / "backend").mkdir()
    (workspace / "backend" / "pom.xml").write_text("<project/>\n", encoding="utf-8")
    (workspace / "Example.java").write_text("class Example {\n}\n", encoding="utf-8")
    git(workspace, "add", "Example.java", "backend/pom.xml")
    git(workspace, "commit", "-m", "initial")
    git(workspace, "push", "-u", "origin", "main")
    return workspace, remote


def probe_container_runtime() -> dict:
    """只探测，不改环境：不启动任何容器 VM，也不降级到宿主 shell。"""

    probes = []
    for runtime, arguments in (("docker", ["docker", "ps"]), ("podman", ["podman", "ps"])):
        executable = shutil.which(runtime)
        if executable is None:
            probes.append(f"{runtime}: 未安装")
            continue
        process = subprocess.run(arguments, capture_output=True, text=True, check=False)
        if process.returncode == 0:
            probes.append(f"{runtime}: 可用")
        else:
            probes.append(f"{runtime}: {process.stderr.strip().splitlines()[0][:120]}")
    available = any(probe.endswith("可用") for probe in probes)
    return {"available": available, "detail": "; ".join(probes)}


class Harness:
    def __init__(self, client: httpx.Client) -> None:
        self.client = client

    def public(self, method: str, path: str, body=None, expected=200):
        response = self.client.request(method, path, json=body)
        if response.status_code != expected:
            raise AssertionError(f"{method} {path}: {response.status_code}")
        return response.json()

    def tool(self, owner_id: str, name: str, arguments: dict, expected=200):
        body = {
            "ownerId": owner_id,
            "idempotencyKey": f"{name}:{uuid.uuid4()}",
            "arguments": arguments,
        }
        response = self.client.post(
            f"/internal/agent-tools/{name}/invoke",
            json=body,
            headers={"X-Internal-Service-Token": INTERNAL_TOKEN},
        )
        if response.status_code != expected:
            raise AssertionError(f"{name}: {response.status_code} {response.text[:300]}")
        return response.json()

    def confirm(self, owner_id: str, action_id: str, expected=200):
        response = self.client.post(
            f"/internal/agent-tool-actions/{action_id}/confirm",
            json={"ownerId": owner_id},
            headers={"X-Internal-Service-Token": INTERNAL_TOKEN},
        )
        if response.status_code != expected:
            raise AssertionError(f"confirm {action_id}: {response.status_code}")
        return response.json()

    def write_confirmed(self, owner_id: str, name: str, arguments: dict) -> dict:
        prepared = self.tool(owner_id, name, arguments)
        action = prepared.get("action") or {}
        assert action.get("status") == "WAITING_CONFIRMATION", f"{name} 未进入待确认: {action}"
        assert action.get("riskLevel") == "HIGH", f"{name} 风险等级不是 HIGH: {action}"
        return self.confirm(owner_id, action["actionId"])


def main() -> int:
    if not INTERNAL_TOKEN:
        print(json.dumps({"result": "BLOCKED", "reason": "缺少 INTERNAL_SERVICE_TOKEN"}, ensure_ascii=False))
        return 2

    root = Path(tempfile.mkdtemp(prefix="studypilot-task32-e2e-"))
    blocked: list[str] = []
    try:
        workspace, remote = build_repository(root)
        head_before = git(workspace, "rev-parse", "HEAD")
        remote_before = git(workspace, "--git-dir", str(remote), "rev-parse", "refs/heads/main")
        record("disposable-repo", "PASS", "已创建一次性工作仓库与本地 bare remote（未触碰主仓库）")

        with httpx.Client(base_url=BASE_URL, timeout=120) as client:
            harness = Harness(client)
            assert harness.public("GET", "/actuator/health")["status"] == "UP"

            user = harness.public(
                "POST",
                "/api/auth/register",
                {
                    "email": f"task32-e2e-{uuid.uuid4()}@example.com",
                    "password": secrets.token_urlsafe(24) + "Aa1!",
                    "displayName": "Task32 独立一次性仓库验收",
                },
                201,
            )
            owner_id = user["user"]["id"]
            client.headers["Authorization"] = "Bearer " + user["accessToken"]
            registered = harness.public(
                "POST",
                "/api/workspaces",
                {"name": "task32-e2e", "rootPath": str(workspace)},
                201,
            )
            workspace_id = registered["id"]
            harness.public(
                "POST",
                "/api/agent-grants",
                {
                    "scopes": ["DEVELOPER_MANAGEMENT", "RUNNER_MANAGEMENT"],
                    # MySQL 侧为 TIMESTAMP，超过 2038 的授权到期时间会被拒绝；
                    # 这里使用 2037，避免与 Task 32 无关的 datetime 溢出。
                    "expiresAt": "2037-01-01T00:00:00Z",
                },
                201,
            )
            record("register-workspace", "PASS", "独立用户 + 一次性工作区登记 + 管理授权")

            # ---- 步骤 1：补丁预览（只读）与确认应用（HIGH） ----
            diff = (
                "--- a/Example.java\n"
                "+++ b/Example.java\n"
                "@@ -1,2 +1,3 @@\n"
                " class Example {\n"
                "+    int value = 1;\n"
                " }\n"
            )
            preview = harness.tool(
                owner_id,
                "developer.patch.preview",
                {"workspaceId": workspace_id, "targetFile": "Example.java", "diff": diff},
            )["data"]
            assert preview["safeToApply"] is True, preview
            assert git(workspace, "diff", "--name-only") == "", "补丁预览不得修改工作区"
            harness.write_confirmed(
                owner_id,
                "developer.patch.apply",
                {
                    "workspaceId": workspace_id,
                    "targetFile": "Example.java",
                    "unifiedDiff": diff,
                    "expectedSha256": preview["expectedSha256"],
                    "explanation": "Task 32 REAL_E2E 补丁",
                },
            )
            assert "int value = 1;" in (workspace / "Example.java").read_text(encoding="utf-8")
            assert git(workspace, "rev-parse", "HEAD") == head_before, "补丁确认不得提交"
            record("patch-preview-confirm", "PASS", "补丁预览只读，确认后文件已变更且未产生提交")

            # ---- 步骤 2：diff 与嵌套项目测试推荐（workingDirectory） ----
            diff_text = harness.tool(
                owner_id, "developer.git.diff", {"workspaceId": workspace_id}
            )["data"]
            assert "int value = 1;" in diff_text["content"], diff_text
            recommendation = harness.tool(
                owner_id,
                "developer.tests.recommend",
                {"workspaceId": workspace_id, "changedFiles": ["backend/src/Main.java"]},
            )["data"]
            executions = [(item["templateType"], item["workingDirectory"]) for item in recommendation["executions"]]
            assert ("MAVEN_TEST", "backend") in executions, recommendation
            record(
                "diff-and-test-recommendation",
                "PASS",
                f"Git diff 真实反映改动；测试推荐绑定子目录 {executions}",
            )

            # ---- 步骤 3：容器测试（无容器运行时则明确 BLOCKED） ----
            runtime_probe = probe_container_runtime()
            record("container-runtime-probe", "PASS" if runtime_probe["available"] else "BLOCKED",
                   runtime_probe["detail"])
            try:
                prepared = harness.tool(
                    owner_id,
                    "runner.check.run",
                    {
                        "workspaceId": workspace_id,
                        "templateType": "MAVEN_TEST",
                        "workingDirectory": "backend",
                        "idempotencyKey": f"task32-runner-{uuid.uuid4()}",
                    },
                )
                action = prepared.get("action") or {}
                if action.get("status") in ("WAITING_CONFIRMATION", "WAITING_AUTHORIZATION"):
                    action = harness.confirm(owner_id, action["actionId"])
                runner_result = action.get("result") if isinstance(action.get("result"), dict) else {}
                runner_result = runner_result or prepared.get("data") or {}
                status = action.get("status") or runner_result.get("status") or "UNKNOWN"
                detail = (
                    action.get("error")
                    or runner_result.get("errorMessage")
                    or runner_result.get("stderr")
                    or runner_result.get("message")
                    or ""
                )
                blocked.append(f"container-runner: actionStatus={status} detail={str(detail)[:240]}")
                record(
                    "container-test",
                    "BLOCKED",
                    f"无可用容器运行时，runner 治理动作收敛为 {status}，未降级为宿主 shell",
                )
            except AssertionError as exception:
                blocked.append(f"container-runner: {exception}")
                record("container-test", "BLOCKED", str(exception)[:200])

            # ---- 步骤 4：commit 预览与确认（不得隐式 push） ----
            commit_preview = harness.tool(
                owner_id,
                "developer.git.commit.preview",
                {"workspaceId": workspace_id, "paths": ["Example.java"], "message": "feat: task32 e2e"},
            )["data"]
            assert commit_preview["expectedHead"] == head_before
            commit_response = harness.write_confirmed(
                owner_id,
                "developer.git.commit",
                {
                    "workspaceId": workspace_id,
                    "paths": ["Example.java"],
                    "message": commit_preview["message"],
                    "expectedHead": commit_preview["expectedHead"],
                    "changeFingerprint": commit_preview["changeFingerprint"],
                },
            )
            assert commit_response["status"] == "SUCCEEDED", commit_response
            head_after_commit = git(workspace, "rev-parse", "HEAD")
            assert head_after_commit != head_before, "确认 commit 必须产生本地提交"
            assert git(workspace, "--git-dir", str(remote), "rev-parse", "refs/heads/main") == remote_before, (
                "确认 commit 不得隐式 push"
            )
            record("commit-confirm", "PASS", "确认 commit 产生本地提交，远端保持不变")

            # ---- 步骤 5：push 预览与确认（绑定全部事实） ----
            push_preview = harness.tool(
                owner_id, "developer.git.push.preview", {"workspaceId": workspace_id}
            )["data"]
            assert push_preview["expectedHead"] == head_after_commit
            assert len(push_preview["remoteUrlDigest"]) == 64
            assert push_preview["expectedRemoteRef"] == "refs/remotes/origin/main"
            assert len(push_preview["expectedRemoteRefCommit"]) == 40
            push_arguments = {
                "workspaceId": workspace_id,
                "remoteName": push_preview["remoteName"],
                "branch": push_preview["branch"],
                "expectedHead": push_preview["expectedHead"],
                "remoteUrlDigest": push_preview["remoteUrlDigest"],
                "expectedRemoteRef": push_preview["expectedRemoteRef"],
                "expectedRemoteRefCommit": push_preview["expectedRemoteRefCommit"],
                "timeoutSeconds": push_preview["timeoutSeconds"],
            }
            push_response = harness.write_confirmed(owner_id, "developer.git.push", push_arguments)
            assert push_response["status"] == "SUCCEEDED", push_response
            remote_after_push = git(workspace, "--git-dir", str(remote), "rev-parse", "refs/heads/main")
            assert remote_after_push == head_after_commit, "push 后远端 ref 必须等于本地 HEAD"
            record("push-confirm", "PASS", "push 确认后远端 ref 等于本地 HEAD")

            # ---- 步骤 6：重复确认不新增提交或推送，并核对治理证据 ----
            executions_before = harness.public("GET", "/api/agent-executions")
            audit_before = harness.public("GET", "/api/audit-logs")
            notifications_before = harness.public("GET", "/api/notifications")
            # 重复确认同一次 commit / push：必须返回同一终态，且不产生第二个提交或第二次推送。
            repeated_commit = harness.confirm(owner_id, commit_response["actionId"])
            repeated_push = harness.confirm(owner_id, push_response["actionId"])
            assert repeated_commit["status"] == "SUCCEEDED", repeated_commit
            assert repeated_commit["result"] == commit_response["result"], "重复确认必须返回同一提交结果"
            assert repeated_push["status"] == "SUCCEEDED", repeated_push
            assert repeated_push["result"] == push_response["result"], "重复确认必须返回同一推送结果"
            assert git(workspace, "rev-parse", "HEAD") == head_after_commit
            assert git(workspace, "--git-dir", str(remote), "rev-parse", "refs/heads/main") == remote_after_push
            assert harness.public("GET", "/api/agent-executions") == executions_before
            assert harness.public("GET", "/api/audit-logs") == audit_before
            assert harness.public("GET", "/api/notifications") == notifications_before
            succeeded = [
                item for item in executions_before if item.get("status") == "SUCCEEDED"
            ]
            execution_types = {item.get("executionType") for item in succeeded}
            assert {"CODE_PATCH_APPLICATION", "GIT_COMMIT", "GIT_PUSH"} <= execution_types, execution_types
            assert any(
                item.get("action") == "EXECUTION_STATUS_CHANGED" for item in audit_before
            ), "必须留下状态变更审计"
            record(
                "governance-evidence",
                "PASS",
                f"SUCCEEDED 执行类型 {sorted(execution_types)}；审计与通知在复查前后一致",
            )

        result = "BLOCKED" if blocked else "PASS"
        print(json.dumps({
            "result": result,
            "scope": "real Java HTTP + real MySQL + real disposable git repo/bare remote",
            "blocked": blocked,
            "not_covered": [
                "真实容器链路（无容器运行时，未降级为宿主 shell）",
                "真实模型调用与 Python 超级visor（不属于 Task 32 范围）",
            ],
            "steps": STEPS,
        }, ensure_ascii=False, indent=2))
        return 0
    finally:
        # 一次性临时仓库全部清理，不留残留样本。
        shutil.rmtree(root, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
