package com.studypilot.automation.idea.registry;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The plugin's own trusted target registry.
 *
 * The local automation service only ever sends an opaque symbolic handle. This registry is
 * the single place that maps a handle to a concrete IDE target, and the concrete value never
 * crosses the socket. Handles are validated strictly, so a path, a run-configuration name, a
 * selector or free text can never be smuggled in as a handle.
 */
public final class IdeaTargetRegistry {

  public enum Kind {
    FILE,
    RUN_CONFIGURATION,
    TEST_RESULT
  }

  private static final Pattern SYMBOLIC = Pattern.compile("^[A-Z0-9_]{1,64}$");
  private static final int MAX_VALUE_LENGTH = 512;

  public static final class RegisteredTarget {
    public final String handle;
    public final Kind kind;
    /** FILE: canonical path. RUN_CONFIGURATION: configuration name. TEST_RESULT: tool window id. */
    public final String value;
    /** TEST_RESULT only: the exact existing content display name bound to this handle. */
    public final String contentName;
    public final String projectRoot;

    RegisteredTarget(String handle, Kind kind, String value, String contentName, String projectRoot) {
      this.handle = handle;
      this.kind = kind;
      this.value = value;
      this.contentName = contentName;
      this.projectRoot = projectRoot;
    }
  }

  private final java.util.Map<String, RegisteredTarget> targets = new java.util.LinkedHashMap<>();

  /** Registers a handle without a secondary identity. Not valid for TEST_RESULT. */
  public void register(String handle, Kind kind, String value, String projectRoot) {
    register(handle, kind, value, null, projectRoot);
  }

  /**
   * Registers a handle. Throws when the handle, value or secondary identity would violate the
   * trust boundary.
   *
   * A TEST_RESULT handle MUST bind both the result tool window and the exact existing content
   * display name, so an unrelated single content can never satisfy the request.
   */
  public void register(String handle, Kind kind, String value, String contentName, String projectRoot) {
    if (handle == null || !SYMBOLIC.matcher(handle).matches()) {
      throw new IllegalArgumentException("handle must be a symbolic key");
    }
    if (kind == null) {
      throw new IllegalArgumentException("kind is required");
    }
    if (value == null || value.trim().isEmpty() || value.length() > MAX_VALUE_LENGTH) {
      throw new IllegalArgumentException("target value must be a bounded non-empty string");
    }
    if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('|') >= 0) {
      throw new IllegalArgumentException("target value must not contain framing characters");
    }
    if (projectRoot == null || projectRoot.trim().isEmpty()) {
      throw new IllegalArgumentException("projectRoot is required");
    }
    if (kind == Kind.TEST_RESULT) {
      if (contentName == null || contentName.trim().isEmpty() || contentName.length() > MAX_VALUE_LENGTH) {
        throw new IllegalArgumentException(
            "TEST_RESULT must bind an exact existing content display name");
      }
    } else if (contentName != null) {
      throw new IllegalArgumentException("only TEST_RESULT targets carry a content identity");
    }
    if (targets.containsKey(handle)) {
      throw new IllegalArgumentException("duplicate handle");
    }
    targets.put(handle, new RegisteredTarget(handle, kind, value, contentName, projectRoot));
  }

  /** Resolves a handle for an expected kind. A kind mismatch is a rejection, not a fallback. */
  public Optional<RegisteredTarget> lookup(String handle, Kind expected) {
    if (handle == null || expected == null) {
      return Optional.empty();
    }
    RegisteredTarget target = targets.get(handle);
    if (target == null || target.kind != expected) {
      return Optional.empty();
    }
    return Optional.of(target);
  }

  public int size() {
    return targets.size();
  }

  /** Maps a frozen action name to the only kind of target it may resolve. */
  public static Kind kindForAction(String action) {
    if (action == null) {
      return null;
    }
    switch (action.toUpperCase(Locale.ROOT)) {
      case "OPEN_REGISTERED_FILE":
        return Kind.FILE;
      case "FOCUS_RUN_CONFIGURATION":
        return Kind.RUN_CONFIGURATION;
      case "SHOW_TEST_RESULT":
        return Kind.TEST_RESULT;
      default:
        return null;
    }
  }
}
