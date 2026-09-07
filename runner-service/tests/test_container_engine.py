from __future__ import annotations

import pytest
from studypilot_runner.container_engine import (
    ContainerEngine,
    ContainerUnavailable,
    ExecutionRejected,
)


def request(**overrides: object) -> dict[str, object]:
    value: dict[str, object] = {
        "executionId": "execution-1",
        "workspaceId": "workspace-1",
        "workspacePath": "/safe/project",
        "templateType": "MAVEN_TEST",
        "commandTokens": ["mvn", "test"],
        "networkDisabled": True,
        "memoryLimit": "512m",
        "cpuLimit": "1.0",
        "timeoutSeconds": 60,
    }
    value.update(overrides)
    return value


def test_builds_hardened_container_command_without_shell_or_host_fallback() -> None:
    engine = ContainerEngine(engine_path="/usr/local/bin/docker")

    command = engine.build_command(request())

    assert command[:3] == ["/usr/local/bin/docker", "run", "--rm"]
    assert ["--network", "none"] == command[command.index("--network") : command.index("--network") + 2]
    assert "--read-only" in command
    assert ["--cap-drop", "ALL"] == command[command.index("--cap-drop") : command.index("--cap-drop") + 2]
    assert ["--security-opt", "no-new-privileges"] == command[command.index("--security-opt") : command.index("--security-opt") + 2]
    assert ["--memory", "512m"] == command[command.index("--memory") : command.index("--memory") + 2]
    assert ["--cpus", "1.0"] == command[command.index("--cpus") : command.index("--cpus") + 2]
    assert ["--pids-limit", "256"] == command[command.index("--pids-limit") : command.index("--pids-limit") + 2]
    assert ["--user", "65532:65532"] == command[command.index("--user") : command.index("--user") + 2]
    assert "type=bind,src=/safe/project,dst=/source,readonly" in command
    assert "/workspace:rw,exec,nosuid,size=1g,mode=1777" in command
    assert "type=volume,src=studypilot-cache-" in " ".join(command)
    assert "MAVEN_OPTS=-Dmaven.repo.local=/cache/m2/repository -Djansi.tmpdir=/workspace/.jansi" in command
    assert command[-3:] == ["studypilot/runner-maven:1", "mvn", "test"]


def test_rejects_changed_command_tokens_and_network_for_test_template() -> None:
    engine = ContainerEngine(engine_path="/usr/local/bin/docker")
    with pytest.raises(ExecutionRejected, match="command"):
        engine.build_command(request(commandTokens=["sh", "-c", "id"]))
    with pytest.raises(ExecutionRejected, match="network"):
        engine.build_command(request(networkDisabled=False))


def test_prepare_dependencies_uses_go_offline_template_with_network() -> None:
    engine = ContainerEngine(engine_path="/usr/local/bin/docker")

    command = engine.build_command(
        request(
            templateType="PREPARE_DEPENDENCIES",
            commandTokens=["mvn", "dependency:go-offline"],
            networkDisabled=False,
        )
    )

    assert command[-3:] == [
        "studypilot/runner-maven:1",
        "mvn",
        "dependency:go-offline",
    ]


def test_fails_closed_when_no_container_engine_is_installed(monkeypatch) -> None:
    monkeypatch.setattr("shutil.which", lambda _: None)
    with pytest.raises(ContainerUnavailable, match="Docker or Podman"):
        ContainerEngine.detect()


def test_stages_source_without_secrets_build_outputs_or_symlinks(tmp_path) -> None:
    source = tmp_path / "source"
    (source / "src").mkdir(parents=True)
    (source / "src" / "Main.java").write_text("class Main {}")
    (source / ".env").write_text("DEEPSEEK_API_KEY=secret")
    (source / "private.pem").write_text("private key")
    (source / "target").mkdir()
    (source / "target" / "compiled.class").write_bytes(b"compiled")
    (source / "link").symlink_to(source / "src", target_is_directory=True)
    engine = ContainerEngine(engine_path="/usr/local/bin/docker")

    with engine.stage_workspace(source) as staged:
        assert (staged / "src" / "Main.java").read_text() == "class Main {}"
        assert not (staged / ".env").exists()
        assert not (staged / "private.pem").exists()
        assert not (staged / "target").exists()
        assert not (staged / "link").exists()


def test_stages_workspace_below_configured_shared_root(tmp_path) -> None:
    source = tmp_path / "source"
    source.mkdir()
    (source / "README.md").write_text("safe")
    staging_root = tmp_path / "container-shared-staging"
    engine = ContainerEngine(
        engine_path="/usr/local/bin/docker",
        staging_root=staging_root,
    )

    with engine.stage_workspace(source) as staged:
        assert staged.is_relative_to(staging_root)
        assert (staged / "README.md").read_text() == "safe"
