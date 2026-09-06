"""仅监听 Unix Socket 的 StudyPilot Local Runner。"""

from __future__ import annotations

import json
import os
import socketserver
import stat
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Protocol

from .container_engine import ContainerEngine
from .protocol import EnvelopeVerifier, ProtocolError


class RequestRejected(ValueError):
    """外层契约或工作区边界不合法。"""


class ExecutionEngine(Protocol):
    def execute(self, request: dict[str, Any]) -> dict[str, object]: ...


class LocalRunnerApplication:
    def __init__(
        self,
        verifier: EnvelopeVerifier,
        engine: ExecutionEngine,
        allowed_roots: list[Path],
    ) -> None:
        if not allowed_roots:
            raise RequestRejected("at least one allowed root is required")
        self._verifier = verifier
        self._engine = engine
        self._allowed_roots = [root.resolve(strict=True) for root in allowed_roots]

    def handle(self, request: dict[str, Any]) -> dict[str, object]:
        envelope = request.get("envelope")
        if not isinstance(envelope, dict):
            raise RequestRejected("missing envelope")
        if (
            request.get("workspaceId") != envelope.get("workspaceId")
            or request.get("templateType") != envelope.get("templateType")
        ):
            raise RequestRejected("outer metadata does not match signed envelope")
        workspace = self._validated_workspace(envelope.get("workspacePath"))
        envelope["workspacePath"] = str(workspace)
        try:
            self._verifier.verify_and_consume(envelope)
        except ProtocolError as exc:
            raise RequestRejected(str(exc)) from exc
        execution = self._engine.execute(envelope)
        success = execution.get("success") is True
        return {
            "executionId": envelope["executionId"],
            "governanceExecutionId": envelope["executionId"],
            "workspaceId": envelope["workspaceId"],
            "templateType": envelope["templateType"],
            "status": "SUCCEEDED" if success else "FAILED",
            "exitCode": int(execution.get("exitCode", -1)),
            "commandTokens": envelope["commandTokens"],
            "stdoutSummary": str(execution.get("stdoutSummary", "")),
            "stderrSummary": str(execution.get("stderrSummary", "")),
            "success": success,
            "durationMillis": int(execution.get("durationMillis", 0)),
            "executedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        }

    def _validated_workspace(self, raw_path: Any) -> Path:
        if not isinstance(raw_path, str):
            raise RequestRejected("workspace path is required")
        supplied = Path(raw_path)
        if not supplied.is_absolute():
            raise RequestRejected("workspace path must be absolute")
        lexical = Path(os.path.abspath(raw_path))
        try:
            canonical = supplied.resolve(strict=True)
        except OSError as exc:
            raise RequestRejected("workspace does not exist") from exc
        if lexical != canonical or not canonical.is_dir():
            raise RequestRejected("workspace must be a canonical directory")
        if not any(canonical == root or canonical.is_relative_to(root) for root in self._allowed_roots):
            raise RequestRejected("workspace is outside allowed roots")
        return canonical


class _UnixServer(socketserver.UnixStreamServer):
    application: LocalRunnerApplication
    max_request_bytes: int = 131_072


class _RequestHandler(socketserver.StreamRequestHandler):
    def handle(self) -> None:
        raw = self.rfile.readline(self.server.max_request_bytes + 1)  # type: ignore[attr-defined]
        if len(raw) > self.server.max_request_bytes:  # type: ignore[attr-defined]
            self._write({"error": "request exceeds safety limit"})
            return
        try:
            request = json.loads(raw)
            if not isinstance(request, dict):
                raise RequestRejected("request must be an object")
            response = self.server.application.handle(request)  # type: ignore[attr-defined]
        except (json.JSONDecodeError, RequestRejected, ValueError, RuntimeError) as exc:
            response = {"error": str(exc)}
        self._write(response)

    def _write(self, response: dict[str, object]) -> None:
        self.wfile.write(json.dumps(response, ensure_ascii=False).encode() + b"\n")


def create_server(socket_path: Path, application: LocalRunnerApplication) -> _UnixServer:
    socket_path = socket_path.absolute()
    parent_existed = socket_path.parent.exists()
    socket_path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    parent_stat = socket_path.parent.stat()
    if parent_stat.st_uid != os.getuid() or socket_path.parent.is_symlink():
        raise RequestRejected("socket directory must be owned by the runner user")
    if parent_existed and stat.S_IMODE(parent_stat.st_mode) != 0o700:
        raise RequestRejected("existing socket directory must use mode 0700")
    if not parent_existed:
        os.chmod(socket_path.parent, 0o700)
    if socket_path.exists():
        if socket_path.is_symlink() or not socket_path.is_socket():
            raise RequestRejected("refusing to replace unsafe socket path")
        socket_path.unlink()
    server = _UnixServer(str(socket_path), _RequestHandler)
    server.application = application
    os.chmod(socket_path, 0o600)
    return server


def serve(
    socket_path: Path,
    signing_secret: str,
    nonce_database: Path,
    allowed_roots: list[Path],
    staging_root: Path | None = None,
) -> None:
    """启动单进程 Unix Socket 服务；绝不绑定 TCP 端口。"""

    verifier = EnvelopeVerifier(signing_secret, nonce_database)
    application = LocalRunnerApplication(
        verifier,
        ContainerEngine.detect(staging_root=staging_root),
        allowed_roots,
    )
    server = create_server(socket_path, application)
    try:
        server.serve_forever(poll_interval=0.25)
    finally:
        server.server_close()
        socket_path.unlink(missing_ok=True)
