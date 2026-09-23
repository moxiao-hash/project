package com.studypilot.automation.idea;

import com.studypilot.automation.idea.platform.PluginConfig;
import com.studypilot.automation.idea.protocol.PluginProtocol;
import com.studypilot.automation.idea.protocol.PluginRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/** Shared test helper that builds correctly signed plugin frames. */
public final class PluginTestFrames {

  private PluginTestFrames() {}

  /** A valid, freshly signed single-line frame for a registered run-configuration handle. */
  public static byte[] validRunFrame(PluginConfig config) {
    return validFrame(config, PluginProtocol.ACTION_FOCUS_RUN, "RUN_REGISTERED");
  }

  /** A valid, freshly signed single-line frame for a registered file handle. */
  public static byte[] validFileFrame(PluginConfig config) {
    return validFrame(config, PluginProtocol.ACTION_OPEN_FILE, "FILE_REGISTERED");
  }

  /** A valid, freshly signed single-line frame for any frozen action and registered handle. */
  public static byte[] validFrame(PluginConfig config, String action, String handle) {
    long now = System.currentTimeMillis();
    PluginProtocol protocol = new PluginProtocol(config.secret);
    PluginRequest unsigned =
        new PluginRequest(
            1,
            UUID.randomUUID().toString(),
            action,
            handle,
            Instant.ofEpochMilli(now).toString(),
            Instant.ofEpochMilli(now + 8000).toString(),
            java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(randomBytes(16)),
            "0".repeat(64));
    String signature = protocol.signatureOf(unsigned);
    PluginRequest signed =
        new PluginRequest(
            unsigned.version,
            unsigned.requestId,
            unsigned.action,
            unsigned.targetKey,
            unsigned.issuedAt,
            unsigned.expiresAt,
            unsigned.nonce,
            signature);
    String json =
        "{"
            + "\"version\":1,"
            + "\"requestId\":\""
            + signed.requestId
            + "\","
            + "\"action\":\""
            + signed.action
            + "\","
            + "\"targetKey\":\""
            + signed.targetKey
            + "\","
            + "\"issuedAt\":\""
            + signed.issuedAt
            + "\","
            + "\"expiresAt\":\""
            + signed.expiresAt
            + "\","
            + "\"nonce\":\""
            + signed.nonce
            + "\","
            + "\"signature\":\""
            + signed.signature
            + "\""
            + "}\n";
    return json.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] randomBytes(int length) {
    byte[] bytes = new byte[length];
    new java.security.SecureRandom().nextBytes(bytes);
    return bytes;
  }
}
