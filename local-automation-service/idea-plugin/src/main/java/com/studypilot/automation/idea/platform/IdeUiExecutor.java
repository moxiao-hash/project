package com.studypilot.automation.idea.platform;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.studypilot.automation.idea.dispatch.UiExecutor;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs IDE work on the IntelliJ UI thread with a hard timeout.
 *
 * The plugin never touches model or UI state off the UI thread, and never blocks the socket
 * handler indefinitely: a timeout or a disposal is reported so the dispatcher can fail closed.
 */
public final class IdeUiExecutor implements UiExecutor {

  @Override
  public <T> T runOnUiThread(Callable<T> task, long timeoutMs) throws UiTimeoutException, IdeDisposedException {
    Application application = ApplicationManager.getApplication();
    if (application == null || application.isDisposed()) {
      throw new IdeDisposedException("application is disposed");
    }

    if (application.isDispatchThread()) {
      try {
        return task.call();
      } catch (RuntimeException e) {
        throw e;
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }

    FutureTask<T> future = new FutureTask<>(task);
    application.invokeLater(future, condition -> !application.isDisposed());
    try {
      T value = future.get(timeoutMs, TimeUnit.MILLISECONDS);
      if (application.isDisposed()) {
        throw new IdeDisposedException("application disposed during the operation");
      }
      return value;
    } catch (TimeoutException e) {
      future.cancel(false);
      throw new UiTimeoutException("IDE operation did not complete within the timeout");
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      }
      throw new IllegalStateException(cause);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting for the IDE UI thread", e);
    }
  }
}
