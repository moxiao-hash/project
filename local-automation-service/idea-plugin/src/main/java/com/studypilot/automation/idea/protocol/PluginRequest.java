package com.studypilot.automation.idea.protocol;

/**
 * One IDEA action request received on the plugin's private Unix Domain Socket.
 *
 * The only fields the local automation service may send are the fixed version, the
 * correlation id, one of the three frozen IDEA actions, an opaque registered handle and the
 * timing/nonce/replay material. Paths, run-configuration names, free text, selectors,
 * IntelliJ Action ids, process ids and window titles are structurally impossible here.
 */
public final class PluginRequest {

  public final int version;
  public final String requestId;
  public final String action;
  public final String targetKey;
  public final String issuedAt;
  public final String expiresAt;
  public final String nonce;
  public final String signature;

  public PluginRequest(
      int version,
      String requestId,
      String action,
      String targetKey,
      String issuedAt,
      String expiresAt,
      String nonce,
      String signature) {
    this.version = version;
    this.requestId = requestId;
    this.action = action;
    this.targetKey = targetKey;
    this.issuedAt = issuedAt;
    this.expiresAt = expiresAt;
    this.nonce = nonce;
    this.signature = signature;
  }

  @Override
  public String toString() {
    return "PluginRequest{version="
        + version
        + ", requestId="
        + requestId
        + ", action="
        + action
        + ", targetKey="
        + targetKey
        + "}";
  }
}
