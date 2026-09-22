package com.studypilot.automation.idea.protocol;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Frozen plugin protocol: framing, field validation, signing and response formatting.
 *
 * This is a SEPARATE protocol domain from the Java-facing socket. The canonical payload is
 * prefixed with a domain marker and covers the plugin field order only, so a signature
 * produced for the Java-facing request can never be replayed here (and vice versa).
 */
public final class PluginProtocol {

  public static final int MAX_FRAME_BYTES = 16384;
  public static final int MIN_SECRET_BYTES = 32;
  /** Short expiry: the plugin link is strictly tighter than the 60 s Java-facing window. */
  public static final long MAX_LIFETIME_MS = 15_000L;
  public static final long MAX_FUTURE_DRIFT_MS = 5_000L;

  public static final String DOMAIN = "studypilot-idea-plugin-v1";

  public static final String ACTION_OPEN_FILE = "OPEN_REGISTERED_FILE";
  public static final String ACTION_FOCUS_RUN = "FOCUS_RUN_CONFIGURATION";
  public static final String ACTION_SHOW_RESULT = "SHOW_TEST_RESULT";

  private static final List<String> REQUIRED_FIELDS =
      List.of("version", "requestId", "action", "targetKey", "issuedAt", "expiresAt", "nonce", "signature");

  private static final Pattern UUID_REGEX =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
  private static final Pattern SYMBOLIC_REGEX = Pattern.compile("^[A-Z0-9_]{1,64}$");
  private static final Pattern UTC_REGEX =
      Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?Z$");
  private static final Pattern NONCE_REGEX = Pattern.compile("^[A-Za-z0-9_-]{22,128}$");
  private static final Pattern SIGNATURE_REGEX = Pattern.compile("^[0-9a-f]{64}$");

  private final byte[] secret;

  public PluginProtocol(byte[] secret) {
    this.secret = secret == null ? new byte[0] : secret.clone();
  }

  public int secretLength() {
    return secret.length;
  }

  public static final class Result<T> {
    public final boolean ok;
    public final T value;
    public final String errorCode;
    public final String message;

    private Result(boolean ok, T value, String errorCode, String message) {
      this.ok = ok;
      this.value = value;
      this.errorCode = errorCode;
      this.message = message;
    }

    static <T> Result<T> ok(T value) {
      return new Result<>(true, value, null, null);
    }

    static <T> Result<T> error(String code, String message) {
      return new Result<>(false, null, code, message);
    }
  }

  /** Parses and validates one single-line request frame. Never throws. */
  public Result<PluginRequest> parse(byte[] rawLine) {
    if (rawLine == null || rawLine.length == 0) {
      return Result.error("INVALID_FRAME", "empty request frame");
    }
    if (rawLine.length > MAX_FRAME_BYTES) {
      return Result.error("OVERSIZE_FRAME", "frame exceeds 16 KiB");
    }

    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(java.nio.ByteBuffer.wrap(rawLine))
              .toString();
    } catch (CharacterCodingException e) {
      return Result.error("INVALID_UTF8", "frame is not valid UTF-8");
    }

    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\n' || c == '\r') {
        return Result.error("INVALID_FRAME", "frame must be exactly one line");
      }
    }

    String trimmed = text.trim();
    if (trimmed.isEmpty()) {
      return Result.error("INVALID_FRAME", "empty request frame");
    }

    Json.Result parsed = Json.parseFlatObject(trimmed);
    if (parsed.reason == Json.Reason.DUPLICATE_KEY) {
      return Result.error("DUPLICATE_KEYS", "duplicate JSON member names");
    }
    if (parsed.reason != Json.Reason.OK) {
      return Result.error("INVALID_JSON", "request must be a flat JSON object of scalars");
    }

    Map<String, Json.Field> byName = new LinkedHashMap<>();
    for (Json.Field field : parsed.fields) {
      byName.put(field.name, field);
    }

    for (String name : byName.keySet()) {
      if (!REQUIRED_FIELDS.contains(name)) {
        return Result.error("UNKNOWN_FIELDS", "unknown field rejected");
      }
    }
    for (String name : REQUIRED_FIELDS) {
      if (!byName.containsKey(name)) {
        return Result.error("MISSING_FIELD", "missing required field");
      }
    }

    Json.Field versionField = byName.get("version");
    if (versionField.type != Json.Type.INTEGER || !"1".equals(versionField.value)) {
      return Result.error("UNSUPPORTED_VERSION", "version must be the integer 1");
    }

    String requestId = stringValue(byName.get("requestId"));
    if (requestId == null || !UUID_REGEX.matcher(requestId).matches()) {
      return Result.error("INVALID_REQUEST_ID", "requestId must be a UUID");
    }

    String action = stringValue(byName.get("action"));
    if (action == null
        || !(ACTION_OPEN_FILE.equals(action)
            || ACTION_FOCUS_RUN.equals(action)
            || ACTION_SHOW_RESULT.equals(action))) {
      return Result.error("INVALID_ACTION", "action must be one of the three frozen IDEA actions");
    }

    String targetKey = stringValue(byName.get("targetKey"));
    if (targetKey == null || !SYMBOLIC_REGEX.matcher(targetKey).matches()) {
      return Result.error("INVALID_TARGET_KEY", "targetKey must be an opaque symbolic handle");
    }

    String issuedAt = stringValue(byName.get("issuedAt"));
    if (issuedAt == null || !UTC_REGEX.matcher(issuedAt).matches()) {
      return Result.error("INVALID_TIMESTAMP", "issuedAt must be a canonical UTC instant");
    }
    String expiresAt = stringValue(byName.get("expiresAt"));
    if (expiresAt == null || !UTC_REGEX.matcher(expiresAt).matches()) {
      return Result.error("INVALID_TIMESTAMP", "expiresAt must be a canonical UTC instant");
    }

    String nonce = stringValue(byName.get("nonce"));
    if (nonce == null || !NONCE_REGEX.matcher(nonce).matches()) {
      return Result.error("INVALID_NONCE", "nonce must be base64url with at least 128 bits");
    }

    String signature = stringValue(byName.get("signature"));
    if (signature == null || !SIGNATURE_REGEX.matcher(signature).matches()) {
      return Result.error("INVALID_SIGNATURE", "signature must be lowercase hex HMAC-SHA256");
    }

    return Result.ok(
        new PluginRequest(1, requestId, action, targetKey, issuedAt, expiresAt, nonce, signature));
  }

  private static String stringValue(Json.Field field) {
    return field != null && field.type == Json.Type.STRING ? field.value : null;
  }

  /**
   * Canonical payload for signing. Field order is fixed and every value is length-prefixed
   * with its UTF-8 byte length, under an explicit domain marker.
   */
  public byte[] canonicalPayload(PluginRequest request) {
    StringBuilder sb = new StringBuilder();
    appendField(sb, DOMAIN);
    appendField(sb, Integer.toString(request.version));
    appendField(sb, request.requestId);
    appendField(sb, request.action);
    appendField(sb, request.targetKey);
    appendField(sb, request.issuedAt);
    appendField(sb, request.expiresAt);
    appendField(sb, request.nonce);
    return sb.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static void appendField(StringBuilder sb, String value) {
    String safe = value == null ? "" : value;
    sb.append(safe.getBytes(StandardCharsets.UTF_8).length).append('#').append(safe).append('|');
  }

  public String signatureOf(PluginRequest request) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      byte[] digest = mac.doFinal(canonicalPayload(request));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return hex.toString();
    } catch (Exception e) {
      throw new IllegalStateException("HMAC-SHA256 unavailable", e);
    }
  }

  /** Full authentication and timing verification. Never throws. */
  public Result<Void> verify(PluginRequest request, long nowMs) {
    if (secret.length < MIN_SECRET_BYTES) {
      return Result.error("KEY_TOO_SHORT", "plugin signing key must be at least 32 bytes");
    }

    long issuedAtMs;
    long expiresAtMs;
    try {
      issuedAtMs = Instant.parse(request.issuedAt).toEpochMilli();
      expiresAtMs = Instant.parse(request.expiresAt).toEpochMilli();
    } catch (DateTimeParseException e) {
      return Result.error("INVALID_TIMESTAMP", "timestamps must be canonical UTC instants");
    }

    if (expiresAtMs < issuedAtMs) {
      return Result.error("INVALID_TIMESTAMP", "expiresAt cannot precede issuedAt");
    }
    if (expiresAtMs - issuedAtMs > MAX_LIFETIME_MS) {
      return Result.error("LIFETIME_EXCEEDED", "plugin request lifetime must not exceed 15 seconds");
    }
    if (issuedAtMs > nowMs + MAX_FUTURE_DRIFT_MS) {
      return Result.error("CLOCK_DRIFT", "issuedAt exceeds the 5-second future drift limit");
    }
    if (nowMs >= expiresAtMs) {
      return Result.error("EXPIRED_REQUEST", "plugin request has expired");
    }

    String expected = signatureOf(request);
    if (!constantTimeEquals(expected, request.signature)) {
      return Result.error("INVALID_SIGNATURE", "HMAC-SHA256 signature mismatch");
    }
    return Result.ok(null);
  }

  private static boolean constantTimeEquals(String a, String b) {
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
  }

  /** Formats a response as a single-line JSON frame ending with a newline, capped at 16 KiB. */
  public String format(PluginResponse response) {
    String message = sanitize(response.message);
    String line = buildLine(response, message);
    if (line.getBytes(StandardCharsets.UTF_8).length > MAX_FRAME_BYTES) {
      line = buildLine(response, "response truncated");
    }
    return line;
  }

  private static String buildLine(PluginResponse response, String message) {
    StringBuilder sb = new StringBuilder(256);
    sb.append('{');
    sb.append("\"version\":1,");
    sb.append("\"requestId\":").append(Json.escape(response.requestId)).append(',');
    sb.append("\"action\":").append(Json.escape(response.action)).append(',');
    sb.append("\"status\":").append(Json.escape(response.status)).append(',');
    sb.append("\"errorCode\":").append(Json.escape(response.errorCode)).append(',');
    sb.append("\"message\":").append(Json.escape(message)).append(',');
    sb.append("\"finishedAt\":").append(Json.escape(response.finishedAt));
    sb.append('}');
    return sb.toString();
  }

  /**
   * Sanitizes a message so it can never carry a path, URL, secret or stack trace, and caps
   * it at 200 characters.
   */
  public static String sanitize(String raw) {
    if (raw == null) {
      return "";
    }
    String value = raw.replaceAll("https?://\\S+", "[URL]");
    value = value.replaceAll("(?:/[A-Za-z0-9._\\-]+)+", "[PATH]");
    value = value.replaceAll("~/?[A-Za-z0-9._\\-/]*", "[PATH]");
    value = value.replaceAll("\\s+at\\s+.*", "");
    value = value.replace('\n', ' ').replace('\r', ' ').trim();
    if (value.length() > 200) {
      value = value.substring(0, 197) + "...";
    }
    return value;
  }
}
