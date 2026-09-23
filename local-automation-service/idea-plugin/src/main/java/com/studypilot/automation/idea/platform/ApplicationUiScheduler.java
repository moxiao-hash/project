package com.studypilot.automation.idea.platform;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.studypilot.automation.idea.dispatch.UiScheduler;

/**
 * Real UI-thread scheduling for a live IDE.
 *
 * Two deliberate choices:
 *   * NO expiration Condition is passed. The platform's {@code invokeLater(Runnable,
 *     Condition)} treats a true condition as "expired", so the previous
 *     {@code condition -> !application.isDisposed()} predicate marked every task expired while
 *     the IDE was healthy and silently dropped all three actions (measured: responses came
 *     back UI_THREAD_TIMEOUT with an idle AWT event thread). Removing the condition makes that
 *     failure mode structurally impossible.
 *   * {@link ModalityState#any()} is used so a background recovery action is not blocked behind
 *     a modal dialog that the service is not allowed to dismiss.
 *
 * Disposal is handled explicitly by {@link IdeUiExecutor}, and a timed-out task is cancelled
 * before it can run, so no late interface side effect is possible.
 */
public final class ApplicationUiScheduler implements UiScheduler {

  @Override
  public void schedule(Runnable runnable) {
    Application application = ApplicationManager.getApplication();
    if (application == null) {
      throw new IllegalStateException("no IntelliJ application is available");
    }
    application.invokeLater(runnable, ModalityState.any());
  }

  @Override
  public boolean isDisposed() {
    Application application = ApplicationManager.getApplication();
    return application == null || application.isDisposed();
  }
}
