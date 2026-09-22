package com.studypilot.automation.idea.dispatch;

import com.studypilot.automation.idea.protocol.NonceLedger;
import com.studypilot.automation.idea.protocol.PluginProtocol;
import com.studypilot.automation.idea.protocol.PluginRequest;
import com.studypilot.automation.idea.protocol.PluginResponse;
import com.studypilot.automation.idea.registry.IdeaTargetRegistry;
import java.time.Instant;
import java.util.Optional;

/**
 * The single decision point for every plugin request.
 *
 * Order of operations, all of which must pass BEFORE any interface side effect:
 *   1. framing already validated by {@link PluginProtocol#parse(byte[])}
 *   2. signature, version, timing window
 *   3. registry resolution of the opaque handle for the action's required kind
 *   4. atomic persistent nonce consumption
 *   5. execution on the IDE UI thread with a hard timeout
 *
 * Success is reported ONLY when the platform both dispatched the registered action and
 * observed the required IDE state afterwards. Any other outcome is {@code FAILED}; a
 * validation failure is {@code REJECTED}. There is no third path, and no fallback of any
 * kind.
 */
public final class IdeActionDispatcher {

  public interface Clock {
    long nowMs();
  }

  private final PluginProtocol protocol;
  private final IdeaTargetRegistry registry;
  private final NonceLedger ledger;
  private final IdePlatform platform;
  private final UiExecutor uiExecutor;
  private final Clock clock;
  private final long uiTimeoutMs;

  public IdeActionDispatcher(
      PluginProtocol protocol,
      IdeaTargetRegistry registry,
      NonceLedger ledger,
      IdePlatform platform,
      UiExecutor uiExecutor,
      Clock clock,
      long uiTimeoutMs) {
    this.protocol = protocol;
    this.registry = registry;
    this.ledger = ledger;
    this.platform = platform;
    this.uiExecutor = uiExecutor;
    this.clock = clock;
    this.uiTimeoutMs = uiTimeoutMs;
  }

  /** Handles one authenticated request. Never throws; always returns a correlated response. */
  public PluginResponse dispatch(PluginRequest request, long nowMs) {
    String finishedAt = Instant.ofEpochMilli(nowMs).toString();

    PluginProtocol.Result<Void> auth = protocol.verify(request, nowMs);
    if (!auth.ok) {
      return PluginResponse.rejected(
          request.requestId, request.action, auth.errorCode, auth.message, finishedAt);
    }

    IdeaTargetRegistry.Kind kind = IdeaTargetRegistry.kindForAction(request.action);
    if (kind == null) {
      return PluginResponse.rejected(
          request.requestId, request.action, "INVALID_ACTION", "action is not a frozen IDEA action", finishedAt);
    }

    Optional<IdeaTargetRegistry.RegisteredTarget> resolved = registry.lookup(request.targetKey, kind);
    if (resolved.isEmpty()) {
      return PluginResponse.rejected(
          request.requestId,
          request.action,
          "TARGET_NOT_REGISTERED",
          "handle is not registered for this action",
          finishedAt);
    }
    IdeaTargetRegistry.RegisteredTarget target = resolved.get();

    // Durable replay protection strictly before the side effect. A validation failure above
    // never reaches this point, so a rejected request can never burn a legitimate nonce.
    long expiresAtMs = Instant.parse(request.expiresAt).toEpochMilli();
    if (!ledger.consume(request.nonce, expiresAtMs)) {
      return PluginResponse.rejected(
          request.requestId, request.action, "REPLAY_DETECTED", "nonce was already consumed", finishedAt);
    }

    IdeOutcome outcome;
    try {
      outcome =
          uiExecutor.runOnUiThread(() -> execute(target, kind), uiTimeoutMs);
    } catch (UiExecutor.UiTimeoutException e) {
      return PluginResponse.failed(
          request.requestId, request.action, "UI_THREAD_TIMEOUT", "IDE operation did not complete in time", finishedAt);
    } catch (UiExecutor.IdeDisposedException e) {
      return PluginResponse.failed(
          request.requestId, request.action, "IDE_DISPOSING", "IDE is shutting down or the project is disposed", finishedAt);
    } catch (RuntimeException e) {
      return PluginResponse.failed(
          request.requestId, request.action, "INTERNAL_ERROR", "IDE operation failed", finishedAt);
    }

    if (outcome == null) {
      return PluginResponse.failed(
          request.requestId, request.action, "INTERNAL_ERROR", "IDE operation returned no outcome", finishedAt);
    }

    if (outcome.isSuccess()) {
      return PluginResponse.succeeded(request.requestId, request.action, finishedAt);
    }
    if (outcome.dispatched) {
      // The action ran but the required IDE state could not be proven: never success.
      return PluginResponse.failed(
          request.requestId, request.action, "UNVERIFIED_TARGET_STATE", outcome.message, finishedAt);
    }
    return PluginResponse.failed(request.requestId, request.action, outcome.code, outcome.message, finishedAt);
  }

  private IdeOutcome execute(IdeaTargetRegistry.RegisteredTarget target, IdeaTargetRegistry.Kind kind) {
    switch (kind) {
      case FILE:
        return platform.openRegisteredFile(target.value, target.projectRoot);
      case RUN_CONFIGURATION:
        return platform.focusRunConfiguration(target.value, target.projectRoot);
      case TEST_RESULT:
        return platform.showTestResult(target.value, target.projectRoot);
      default:
        return IdeOutcome.refused("INVALID_ACTION", "unsupported target kind");
    }
  }
}
