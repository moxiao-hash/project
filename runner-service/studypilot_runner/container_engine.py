"""只允许固定模板的 Docker/Podman 执行器。"""

from __future__ import annotations

import hashlib
import os
import re
import shutil
import signal
import subprocess
import tempfile
import time
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path
from typing import Any


class ContainerUnavailable(RuntimeError):
    """本机没有可用容器引擎。"""


class ExecutionRejected(ValueError):
    """请求不符合固定 Runner 策略。"""


_IMAGES = {
    "MAVEN_TEST": "studypilot/runner-maven:1",
    "MAVEN_COMPILE": "studypilot/runner-maven:1",
    "PREPARE_DEPENDENCIES": "studypilot/runner-maven:1",
    "NPM_TEST": "studypilot/runner-node:1",
    "PYTEST": "studypilot/runner-python:1",
}
_BASE_TOKENS = {
    "MAVEN_TEST": ["mvn", "test"],
    "MAVEN_COMPILE": ["mvn", "test-compile"],
    "PREPARE_DEPENDENCIES": ["mvn", "dependency:resolve"],
    "NPM_TEST": ["npm", "test"],
    "PYTEST": ["pytest"],
}
_SAFE_TARGET = re.compile(r"[A-Za-z0-9_.$*?\-]{1,255}")
_EXCLUDED_DIRECTORIES = {
    ".git",
    ".idea",
    ".ssh",
    ".aws",
    ".venv",
    "venv",
    "node_modules",
    "target",
    "dist",
    "__pycache__",
}


class ContainerEngine:
    def __init__(self, engine_path: str) -> None:
        path = Path(engine_path)
        if not path.is_absolute():
            raise ExecutionRejected("container engine path must be absolute")
        self._engine_path = str(path)

    @classmethod
    def detect(cls) -> ContainerEngine:
        for name in ("docker", "podman"):
            located = shutil.which(name)
            if located:
                return cls(located)
        raise ContainerUnavailable("Docker or Podman is required; host fallback is forbidden")

    def build_command(self, request: dict[str, Any]) -> list[str]:
        template = request.get("templateType")
        tokens = request.get("commandTokens")
        if template not in _IMAGES or not isinstance(tokens, list):
            raise ExecutionRejected("unknown command template")
        if not self._tokens_allowed(str(template), tokens):
            raise ExecutionRejected("command tokens do not match the signed template")
        network_disabled = request.get("networkDisabled") is True
        if template != "PREPARE_DEPENDENCIES" and not network_disabled:
            raise ExecutionRejected("network must be disabled for test and build templates")
        if request.get("memoryLimit") != "512m" or request.get("cpuLimit") != "1.0":
            raise ExecutionRejected("unsupported resource limits")
        timeout = request.get("timeoutSeconds")
        if not isinstance(timeout, int) or timeout < 1 or timeout > 300:
            raise ExecutionRejected("unsafe timeout")
        workspace = Path(str(request.get("workspacePath") or ""))
        if not workspace.is_absolute():
            raise ExecutionRejected("workspace must be absolute")

        workspace_id = str(request.get("workspaceId") or "")
        if not workspace_id:
            raise ExecutionRejected("workspace id is required")
        cache_name = "studypilot-cache-" + hashlib.sha256(workspace_id.encode()).hexdigest()[:20]
        command = [self._engine_path, "run", "--rm"]
        if network_disabled:
            command.extend(["--network", "none"])
        command.extend(
            [
                "--read-only",
                "--cap-drop",
                "ALL",
                "--security-opt",
                "no-new-privileges",
                "--pids-limit",
                "256",
                "--memory",
                "512m",
                "--cpus",
                "1.0",
                "--user",
                "65532:65532",
                "--tmpfs",
                "/tmp:rw,noexec,nosuid,size=64m",
                "--tmpfs",
                "/workspace:rw,exec,nosuid,size=1g,uid=65532,gid=65532",
                "--workdir",
                "/workspace",
                "--mount",
                f"type=bind,src={workspace},dst=/source,readonly",
                "--mount",
                f"type=volume,src={cache_name},dst=/cache",
                "--env",
                "MAVEN_CONFIG=/cache/m2",
                "--env",
                "npm_config_cache=/cache/npm",
                _IMAGES[str(template)],
                *tokens,
            ]
        )
        return command

    def execute(self, request: dict[str, Any]) -> dict[str, object]:
        """执行容器客户端；输出写入临时文件，避免子进程撑爆服务内存。"""

        source_workspace = Path(str(request.get("workspacePath") or ""))
        timeout = int(request["timeoutSeconds"])
        name = self._container_name(str(request.get("executionId") or ""))
        safe_environment = {"PATH": "/usr/local/bin:/usr/bin:/bin"}
        started = time.monotonic()
        with self.stage_workspace(source_workspace) as staged_workspace:
            staged_request = dict(request)
            staged_request["workspacePath"] = str(staged_workspace)
            command = self.build_command(staged_request)
            command[3:3] = ["--name", name]
            with tempfile.TemporaryFile() as stdout_file, tempfile.TemporaryFile() as stderr_file:
                process = subprocess.Popen(
                    command,
                    stdin=subprocess.DEVNULL,
                    stdout=stdout_file,
                    stderr=stderr_file,
                    env=safe_environment,
                    start_new_session=True,
                )
                timed_out = False
                try:
                    exit_code = process.wait(timeout=timeout)
                except subprocess.TimeoutExpired:
                    timed_out = True
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=5)
                    self._remove_container(name, safe_environment)
                    exit_code = 124
                stdout = self._read_summary(stdout_file)
                stderr = self._read_summary(stderr_file)
        if timed_out:
            stderr = (stderr + "\nexecution timed out").strip()
        return {
            "exitCode": exit_code,
            "stdoutSummary": stdout,
            "stderrSummary": stderr,
            "success": exit_code == 0,
            "durationMillis": int((time.monotonic() - started) * 1000),
        }

    @contextmanager
    def stage_workspace(self, source: Path) -> Iterator[Path]:
        """复制允许的源码到短生命周期目录，并排除凭据、缓存和构建输出。"""

        canonical = source.resolve(strict=True)
        if not canonical.is_dir() or canonical != Path(os.path.abspath(source)):
            raise ExecutionRejected("workspace must remain a canonical directory")
        with tempfile.TemporaryDirectory(prefix="studypilot-runner-", dir="/tmp") as temporary:
            destination = Path(temporary) / "source"
            destination.mkdir(mode=0o700)
            staged_directories = [destination]
            file_count = 0
            total_bytes = 0
            for current_root, directories, files in os.walk(canonical, followlinks=False):
                current = Path(current_root)
                directories[:] = [
                    name
                    for name in directories
                    if name not in _EXCLUDED_DIRECTORIES
                    and not (current / name).is_symlink()
                ]
                relative = current.relative_to(canonical)
                target_directory = destination / relative
                target_directory.mkdir(mode=0o700, parents=True, exist_ok=True)
                staged_directories.append(target_directory)
                for filename in files:
                    source_file = current / filename
                    if self._excluded_file(source_file, canonical) or source_file.is_symlink():
                        continue
                    metadata = source_file.stat()
                    if not source_file.is_file():
                        continue
                    file_count += 1
                    total_bytes += metadata.st_size
                    if file_count > 20_000 or total_bytes > 200 * 1024 * 1024:
                        raise ExecutionRejected("workspace exceeds staging safety limits")
                    target_file = target_directory / filename
                    shutil.copy2(source_file, target_file)
                    executable = bool(metadata.st_mode & 0o111)
                    target_file.chmod(0o555 if executable else 0o444)
            for directory in reversed(staged_directories):
                directory.chmod(0o555)
            yield destination

    def _excluded_file(self, path: Path, root: Path) -> bool:
        name = path.name
        if name == ".env" or (name.startswith(".env.") and name != ".env.example"):
            return True
        if name in {
            "http-client.private.env.json",
            "id_rsa",
            "id_ed25519",
            ".npmrc",
            ".pypirc",
            "settings.xml",
            "gradle.properties",
        }:
            return True
        if path.suffix.lower() in {".pem", ".key", ".p12", ".pfx", ".jks", ".keystore"}:
            return True
        relative = path.relative_to(root).as_posix()
        return relative.endswith("src/main/resources/application.properties") or bool(
            re.search(r"(^|/)application-(local|prod|production|secret)\.(properties|ya?ml)$", relative)
        )

    def _remove_container(self, name: str, environment: dict[str, str]) -> None:
        subprocess.run(
            [self._engine_path, "rm", "-f", name],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            env=environment,
            timeout=10,
            check=False,
        )

    def _read_summary(self, file_object: Any, limit: int = 65_536) -> str:
        file_object.seek(0)
        data = file_object.read(limit + 1)
        suffix = b"\n[output truncated]" if len(data) > limit else b""
        return (data[:limit] + suffix).decode("utf-8", errors="replace")

    def _container_name(self, execution_id: str) -> str:
        safe = re.sub(r"[^a-zA-Z0-9_.-]", "-", execution_id)[:48]
        if not safe:
            raise ExecutionRejected("invalid execution id")
        return f"studypilot-{safe}"

    def _tokens_allowed(self, template: str, tokens: list[Any]) -> bool:
        if not all(isinstance(token, str) for token in tokens):
            return False
        base = _BASE_TOKENS[template]
        if tokens == base:
            return True
        if template == "MAVEN_TEST" and len(tokens) == 3 and tokens[:2] == base:
            return tokens[2].startswith("-Dtest=") and bool(_SAFE_TARGET.fullmatch(tokens[2][7:]))
        if template == "PYTEST" and len(tokens) == 3 and tokens[:2] == ["pytest", "-k"]:
            return bool(_SAFE_TARGET.fullmatch(tokens[2]))
        return False
