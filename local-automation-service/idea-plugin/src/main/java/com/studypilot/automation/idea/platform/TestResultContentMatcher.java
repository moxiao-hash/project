package com.studypilot.automation.idea.platform;

import java.util.List;

/**
 * Binds a registered test-result handle to ONE exact existing result content.
 *
 * The registered identity is a bounded exact display name. A content is admissible only when
 * it is valid AND its component belongs to the IntelliJ test-framework UI package, so a plain
 * console or an editor view that happens to share the name is never accepted. Zero matches and
 * multiple matches both fail closed: there is deliberately no "the only content" fallback.
 *
 * This class is deliberately independent of the IntelliJ API so the decision can be tested
 * exhaustively; {@code IdeaPlatformOperations} only supplies the observed content views.
 */
public final class TestResultContentMatcher {

  /** Supported IntelliJ test-framework UI package; anything else is not a test result view. */
  public static final String TEST_RESULT_COMPONENT_PREFIX = "com.intellij.execution.testframework";

  public static final int MAX_IDENTITY_LENGTH = 128;

  public enum Outcome {
    MATCHED,
    NOT_FOUND,
    AMBIGUOUS,
    NOT_A_TEST_RESULT,
    INVALID_REGISTRATION
  }

  public static final class ContentView {
    public final String displayName;
    public final String componentClassName;
    public final boolean valid;

    public ContentView(String displayName, String componentClassName, boolean valid) {
      this.displayName = displayName;
      this.componentClassName = componentClassName;
      this.valid = valid;
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
      if (!isTestResultComponent(content.componentClassName)) {
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

  private static boolean isTestResultComponent(String componentClassName) {
    return componentClassName != null && componentClassName.startsWith(TEST_RESULT_COMPONENT_PREFIX);
  }
}
