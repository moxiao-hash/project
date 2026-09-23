package com.studypilot.automation.idea.dispatch;

/**
 * Schedules work onto the IDE's UI thread.
 *
 * Deliberately narrow and condition-free: the platform's {@code invokeLater(Runnable,
 * Condition)} overload takes an EXPIRATION predicate, and a wrong predicate silently drops
 * every queued task while the IDE is perfectly healthy. That was a measured production defect
 * (all actions timed out although the AWT event thread was idle), so this seam does not expose
 * any expiration condition at all; disposal is handled explicitly by {@link IdeUiExecutor}.
 */
public interface UiScheduler {

  /** Queues the runnable on the IDE UI thread. Must not block the calling thread. */
  void schedule(Runnable runnable);

  /** True when the application is shutting down and no further work may be queued. */
  boolean isDisposed();
}
