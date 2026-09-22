package com.studypilot.automation.idea.dispatch;

/**
 * Result of one IDE operation: whether it was dispatched, and whether the REQUIRED IDE state
 * was actually observed afterwards. Success is only ever {@code dispatched && verified}.
 */
public final class IdeOutcome {

  public final boolean dispatched;
  public final boolean verified;
  public final String code;
  public final String message;

  private IdeOutcome(boolean dispatched, boolean verified, String code, String message) {
    this.dispatched = dispatched;
    this.verified = verified;
    this.code = code;
    this.message = message;
  }

  /** The action ran and the post-state was observed. */
  public static IdeOutcome verified() {
    return new IdeOutcome(true, true, "OK", "verified");
  }

  /** The action ran but the required post-state could not be proven. */
  public static IdeOutcome unverified(String code, String message) {
    return new IdeOutcome(true, false, code, message);
  }

  /** The action was never dispatched, or its own guard refused. */
  public static IdeOutcome refused(String code, String message) {
    return new IdeOutcome(false, false, code, message);
  }

  public boolean isSuccess() {
    return dispatched && verified;
  }

  @Override
  public String toString() {
    return "IdeOutcome{dispatched=" + dispatched + ", verified=" + verified + ", code=" + code + "}";
  }
}
