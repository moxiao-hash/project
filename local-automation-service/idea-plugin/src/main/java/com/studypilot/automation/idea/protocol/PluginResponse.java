package com.studypilot.automation.idea.protocol;

/**
 * One IDEA action response written back to the local automation service.
 *
 * {@code status} is the plugin-local status. Only {@code SUCCEEDED} means the plugin
 * dispatched the registered action AND observed the required IDE state afterwards. The
 * local automation service maps it onto the external {@code IDEA_ACCESSIBILITY} receipt.
 */
public final class PluginResponse {

  public static final String SUCCEEDED = "SUCCEEDED";
  public static final String FAILED = "FAILED";
  public static final String REJECTED = "REJECTED";

  public final int version = 1;
  public final String requestId;
  public final String action;
  public final String status;
  public final String errorCode;
  public final String message;
  public final String finishedAt;

  private PluginResponse(
      String requestId, String action, String status, String errorCode, String message, String finishedAt) {
    this.requestId = requestId;
    this.action = action;
    this.status = status;
    this.errorCode = errorCode;
    this.message = message;
    this.finishedAt = finishedAt;
  }

  public static PluginResponse succeeded(String requestId, String action, String finishedAt) {
    return new PluginResponse(
        requestId, action, SUCCEEDED, null, "registered action executed and verified", finishedAt);
  }

  public static PluginResponse failed(
      String requestId, String action, String errorCode, String message, String finishedAt) {
    return new PluginResponse(requestId, action, FAILED, errorCode, message, finishedAt);
  }

  public static PluginResponse rejected(
      String requestId, String action, String errorCode, String message, String finishedAt) {
    return new PluginResponse(requestId, action, REJECTED, errorCode, message, finishedAt);
  }

  public boolean isSuccess() {
    return SUCCEEDED.equals(status);
  }
}
