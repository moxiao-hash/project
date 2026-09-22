package com.studypilot.automation.idea.dispatch;

/**
 * The only IDE capability surface the dispatcher may use.
 *
 * Implementations must use IntelliJ Platform APIs and must revalidate the target against the
 * live project, then observe the required post-state. Implementations must never run or debug
 * tests, never execute a generic action, and never accept anything but the trusted values
 * handed over by {@link IdeActionDispatcher}.
 */
public interface IdePlatform {

  /** Open the registered regular file and verify it became the selected file of the project. */
  IdeOutcome openRegisteredFile(String canonicalPath, String projectRoot);

  /** Focus the one existing matching RunConfiguration without running or editing it. */
  IdeOutcome focusRunConfiguration(String configurationName, String projectRoot);

  /** Reveal the already existing, registered test-result content; never start tests. */
  IdeOutcome showTestResult(String resultContentName, String projectRoot);
}
