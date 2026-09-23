package com.studypilot.automation.idea.platform;

import java.util.List;

/**
 * Binds a registered test-result handle to ONE exact existing result content.
 *
 * Recognition is TYPE-BACKED and never based on the content's wrapper component class:
 *   * the content must be valid and its display name must equal the registered name EXACTLY;
 *   * a {@code RunContentDescriptor} must be linked to the content (by component identity), so
 *     an editor or unrelated content can never satisfy it;
 *   * the descriptor's {@code ExecutionConsole} must actually be a test console, which the
 *     platform layer decides with {@code instanceof BaseTestsOutputConsoleView}.
 *
 * The live measurement that motivated this: the Run tool window content is wrapped in a plain
 * container, so inspecting the wrapper's class name reported "not a test result" even for a
 * genuine test result view. The console object - not the wrapper - carries the real type.
 *
 * Zero matches and multiple matches both fail closed: there is deliberately no
 * "the only content" fallback. Unknown layouts stay failed closed.
 *
 * This class is deliberately independent of the IntelliJ API so the decision can be tested
 * exhaustively; {@code IdeaPlatformOperations} supplies the observed, type-backed facts.
 */
public final class TestResultContentMatcher {

  /** Canonical platform type of a real test console; recorded here for the guard and docs. */
  public static final String TEST_CONSOLE_BASE_TYPE =
      "com.intellij.execution.testframework.ui.BaseTestsOutputConsoleView";

  public static final int MAX_IDENTITY_LENGTH = 128;

  public enum Outcome {
    MATCHED,
    NOT_FOUND,
    AMBIGUOUS,
    NOT_A_TEST_RESULT,
    INVALID_REGISTRATION
  }

  /**
   * One observed content, described only by the facts the platform layer can prove.
   *
   * {@code componentClassName} exists purely for the bounded operator diagnostic; it is NEVER
   * used as the recognition signal.
   */
  public static final class ContentView {
    public final String displayName;
    public final boolean valid;
    public final boolean descriptorLinked;
    public final boolean testConsole;
    public final String componentClassName;

    public ContentView(
        String displayName,
        boolean valid,
        boolean descriptorLinked,
        boolean testConsole,
        String componentClassName) {
      this.displayName = displayName;
      this.valid = valid;
      this.descriptorLinked = descriptorLinked;
      this.testConsole = testConsole;
      this.componentClassName = componentClassName;
    }

    /** True only when this content is provably an existing test-result view. */
    public boolean isTestResultView() {
      return valid && descriptorLinked && testConsole;
    }
  }

  public static final class Result {
    public final Outcome outcome;
    public final int index;

    private Result(Outcome outcome, int index) {
      this.outcome = outcome;
      this.index = index;
    }
  }

  private TestResultContentMatcher() {}

  public static Result match(List<ContentView> contents, String registeredDisplayName) {
    if (registeredDisplayName == null
        || registeredDisplayName.trim().isEmpty()
        || registeredDisplayName.length() > MAX_IDENTITY_LENGTH) {
      return new Result(Outcome.INVALID_REGISTRATION, -1);
    }

    int matchedIndex = -1;
    int matchedCount = 0;
    boolean sameNameNonTest = false;

    for (int i = 0; i < contents.size(); i++) {
      ContentView content = contents.get(i);
      if (content == null || !content.valid || content.displayName == null) {
        continue;
      }
      if (!registeredDisplayName.equals(content.displayName)) {
        continue;
      }
      if (!content.isTestResultView()) {
        sameNameNonTest = true;
        continue;
      }
      matchedCount++;
      if (matchedCount == 1) {
        matchedIndex = i;
      }
    }

    if (matchedCount == 1) {
      return new Result(Outcome.MATCHED, matchedIndex);
    }
    if (matchedCount > 1) {
      return new Result(Outcome.AMBIGUOUS, -1);
    }
    return new Result(sameNameNonTest ? Outcome.NOT_A_TEST_RESULT : Outcome.NOT_FOUND, -1);
  }
}
