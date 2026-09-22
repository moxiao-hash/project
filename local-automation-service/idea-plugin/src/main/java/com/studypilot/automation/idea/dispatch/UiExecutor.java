package com.studypilot.automation.idea.dispatch;

import java.util.concurrent.Callable;

/**
 * Runs one operation on the IDE's UI thread with a hard timeout, and reports disposal.
 *
 * The plugin must never touch IntelliJ model/UI state off the UI thread, and must never hang
 * the socket handler if the IDE is busy or shutting down.
 */
public interface UiExecutor {

  final class UiTimeoutException extends Exception {
    public UiTimeoutException(String message) {
      super(message);
    }
  }

  final class IdeDisposedException extends Exception {
    public IdeDisposedException(String message) {
      super(message);
    }
  }

  <T> T runOnUiThread(Callable<T> task, long timeoutMs) throws UiTimeoutException, IdeDisposedException;
}
