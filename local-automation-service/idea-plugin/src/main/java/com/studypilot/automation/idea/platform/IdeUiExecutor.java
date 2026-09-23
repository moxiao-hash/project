package com.studypilot.automation.idea.platform;

import com.studypilot.automation.idea.dispatch.UiExecutor;
import com.studypilot.automation.idea.dispatch.UiScheduler;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs IDE work on the IntelliJ UI thread with a hard timeout.
 *
 * Invariants:
 *   * the socket thread never performs IDE work itself — the callable is always queued onto
 *     the UI thread, and the caller only waits with a bounded timeout;
 *   * a disposed application fails closed before anything is queued, and disposal observed
 *     during the wait also fails closed;
 *   * a timed-out task is CANCELLED, so a queued {@link FutureTask} that the UI thread picks up
 *     later becomes a no-op. A timed-out action therefore cannot produce a late interface
 *     side effect, which is what lets the caller report an honest FAILED receipt.
 */
public final class IdeUiExecutor implements UiExecutor {

  private final UiScheduler scheduler;

  public IdeUiExecutor() {
    this(new ApplicationUiScheduler());
  }

  public IdeUiExecutor(UiScheduler scheduler) {
    this.scheduler = scheduler;
  }

  @Override
  public <T> T runOnUiThread(Callable<T> task, long timeoutMs)
      throws UiTimeoutException, IdeDisposedException {
    if (scheduler.isDisposed()) {
      throw new IdeDisposedException("application is disposed");
    }

    FutureTask<T> future = new FutureTask<>(task);
    try {
      scheduler.schedule(future);
    } catch (RuntimeException e) {
      if (scheduler.isDisposed()) {
        throw new IdeDisposedException("application disposed while scheduling the operation");
      }
      throw e;
    }

    try {
      T value = future.get(timeoutMs, TimeUnit.MILLISECONDS);
      if (scheduler.isDisposed()) {
        throw new IdeDisposedException("application disposed during the operation");
      }
      return value;
    } catch (TimeoutException e) {
      // Cancelling makes the queued FutureTask a no-op if the UI thread reaches it later.
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
