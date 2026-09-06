from __future__ import annotations

import os
from pathlib import Path

from .server import serve


def main() -> None:
    secret = os.environ.get("STUDYPILOT_RUNNER_SIGNING_SECRET")
    roots = os.environ.get("STUDYPILOT_RUNNER_ALLOWED_ROOTS")
    if not secret or not roots:
        raise SystemExit(
            "STUDYPILOT_RUNNER_SIGNING_SECRET and STUDYPILOT_RUNNER_ALLOWED_ROOTS are required"
        )
    socket_path = Path(
        os.environ.get("STUDYPILOT_RUNNER_SOCKET_PATH", "/tmp/studypilot-runner/runner.sock")
    )
    nonce_database = Path(
        os.environ.get("STUDYPILOT_RUNNER_NONCE_DB", "/tmp/studypilot-runner/nonces.sqlite3")
    )
    allowed_roots = [Path(value) for value in roots.split(os.pathsep) if value]
    serve(socket_path, secret, nonce_database, allowed_roots)


if __name__ == "__main__":
    main()
