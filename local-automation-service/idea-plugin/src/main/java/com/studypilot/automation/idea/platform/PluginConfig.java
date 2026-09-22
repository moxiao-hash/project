package com.studypilot.automation.idea.platform;

import com.studypilot.automation.idea.registry.IdeaTargetRegistry;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Trusted, plugin-side configuration.
 *
 * Parsed in two phases so row order can never matter:
 *   phase 1 — strict UTF-8 decode, framing check, scalar collection (duplicate and unknown
 *             scalar keys rejected);
 *   phase 2 — the canonical, symlink-free project root is established, then every target row
 *             is registered, at which point FILE targets are canonicalised and validated.
 *
 * Layout (tab separated, '#' comments, blank lines ignored):
 *   socketPath      <absolute path>
 *   ledgerPath      <absolute path>
 *   projectRoot     <absolute path of the open project>
 *   uiTimeoutMs     <250..30000>            (optional)
 *   secretFile      <absolute path>         (optional; otherwise the environment variable is used)
 *   <HANDLE>  FILE             <absolute path of an existing regular file inside projectRoot>
 *   <HANDLE>  RUN_CONFIGURATION <existing run configuration name>
 *   <HANDLE>  TEST_RESULT       <result tool window id>  <exact existing content display name>
 *
 * Never logged, never echoed in a response.
 */
public final class PluginConfig {

  public static final String ENV_SECRET = "STUDYPILOT_IDEA_PLUGIN_HMAC_SECRET";

  private static final Set<String> SCALAR_KEYS =
      Set.of("socketPath", "ledgerPath", "projectRoot", "uiTimeoutMs", "secretFile");
  private static final long MIN_UI_TIMEOUT_MS = 250L;
  private static final long MAX_UI_TIMEOUT_MS = 30_000L;
  private static final long DEFAULT_UI_TIMEOUT_MS = 2_000L;

  public final Path configFile;
  public final Path socketPath;
  public final Path ledgerPath;
  public final Path projectRoot;
  public final byte[] secret;
  public final long uiTimeoutMs;
  public final IdeaTargetRegistry registry;

  private PluginConfig(
      Path configFile,
      Path socketPath,
      Path ledgerPath,
      Path projectRoot,
      byte[] secret,
      long uiTimeoutMs,
      IdeaTargetRegistry registry) {
    this.configFile = configFile;
    this.socketPath = socketPath;
    this.ledgerPath = ledgerPath;
    this.projectRoot = projectRoot;
    this.secret = secret;
    this.uiTimeoutMs = uiTimeoutMs;
    this.registry = registry;
  }

  /** Default location of the operator-provided configuration file. */
  public static Path defaultConfigFile() {
    return Path.of(System.getProperty("user.home"), "Library", "Application Support", "StudyPilot", "idea-plugin.tsv");
  }

  public static PluginConfig load(Path configFile, Map<String, String> env) throws IOException {
    if (configFile == null) {
      throw new IOException("plugin configuration file is missing");
    }
    if (Files.isSymbolicLink(configFile)) {
      throw new IOException("plugin configuration file must not be a symlink");
    }
    if (!Files.isRegularFile(configFile)) {
      throw new IOException("plugin configuration file is missing or not a regular file");
    }
    Path canonicalConfigFile = PathBinding.canonical(configFile, true);
    PathBinding.requireRegularFile(canonicalConfigFile);

    List<String> lines = decodeStrictly(Files.readAllBytes(canonicalConfigFile));

    // ---- phase 1: scalars -------------------------------------------------
    Map<String, String> scalars = new LinkedHashMap<>();
    List<Row> targetRows = new ArrayList<>();

    for (String raw : lines) {
      String line = stripCarriageReturn(raw);
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        continue;
      }
      String[] parts = line.split("\t", -1);
      if (parts.length == 2) {
        String key = parts[0].trim();
        String value = parts[1].trim();
        if (key.isEmpty() || value.isEmpty()) {
          throw new IOException("scalar entries must have a non-empty key and value");
        }
        if (!SCALAR_KEYS.contains(key)) {
          throw new IOException("unknown configuration key rejected");
        }
        if (scalars.put(key, value) != null) {
          throw new IOException("duplicate configuration key rejected");
        }
        continue;
      }
      if (parts.length == 3 || parts.length == 4) {
        String handle = parts[0].trim();
        String kind = parts[1].trim();
        String value = parts[2].trim();
        String detail = parts.length == 4 ? parts[3].trim() : "";
        if (handle.isEmpty() || kind.isEmpty() || value.isEmpty()) {
          throw new IOException("target entries must have a non-empty handle, kind and value");
        }
        targetRows.add(new Row(handle, kind, value, detail, parts.length));
        continue;
      }
      throw new IOException("malformed configuration line rejected");
    }

    String rawProjectRoot = requireScalar(scalars, "projectRoot");
    Path projectRoot = PathBinding.canonical(Path.of(rawProjectRoot), true);
    PathBinding.requireDirectory(projectRoot);
    if (!Files.isDirectory(projectRoot, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("projectRoot must be a directory");
    }

    Path rawSocket = Path.of(requireScalar(scalars, "socketPath"));
    if (!rawSocket.isAbsolute()) {
      throw new IOException("socketPath must be an absolute path");
    }
    Path socketParent = rawSocket.toAbsolutePath().normalize().getParent();
    if (socketParent == null) {
      throw new IOException("socketPath must have a parent directory");
    }
    if (!Files.exists(socketParent, LinkOption.NOFOLLOW_LINKS)) {
      Files.createDirectories(socketParent);
      com.studypilot.automation.idea.protocol.NonceLedger.setOwnerOnlyDirectory(socketParent);
    }
    Path canonicalSocketParent = PathBinding.canonical(socketParent, true);
    PathBinding.requireDirectory(canonicalSocketParent);
    Path socketPath = canonicalSocketParent.resolve(rawSocket.getFileName());

    Path rawLedger = Path.of(requireScalar(scalars, "ledgerPath"));
    if (!rawLedger.isAbsolute()) {
      throw new IOException("ledgerPath must be an absolute path");
    }
    Path ledgerParent = rawLedger.toAbsolutePath().normalize().getParent();
    if (ledgerParent == null) {
      throw new IOException("ledgerPath must have a parent directory");
    }
    if (!Files.exists(ledgerParent, LinkOption.NOFOLLOW_LINKS)) {
      Files.createDirectories(ledgerParent);
      com.studypilot.automation.idea.protocol.NonceLedger.setOwnerOnlyDirectory(ledgerParent);
    }
    Path canonicalLedgerParent = PathBinding.canonical(ledgerParent, true);
    PathBinding.requireDirectory(canonicalLedgerParent);
    Path ledgerPath = canonicalLedgerParent.resolve(rawLedger.getFileName());

    long uiTimeoutMs = resolveTimeout(scalars.get("uiTimeoutMs"));
    byte[] secret = resolveSecret(scalars.get("secretFile"), env);
    if (secret.length < 32) {
      throw new IOException("plugin signing key must be at least 32 bytes");
    }

    // ---- phase 2: targets -------------------------------------------------
    IdeaTargetRegistry registry = new IdeaTargetRegistry();
    for (Row row : targetRows) {
      try {
        registerRow(registry, row, projectRoot);
      } catch (IllegalArgumentException e) {
        // Duplicate handles and boundary violations are configuration errors.
        throw new IOException("invalid target row rejected: " + e.getMessage());
      }
    }

    return new PluginConfig(canonicalConfigFile, socketPath, ledgerPath, projectRoot, secret, uiTimeoutMs, registry);
  }

  private static void registerRow(IdeaTargetRegistry registry, Row row, Path projectRoot) throws IOException {
    switch (row.kind) {
      case "FILE": {
        if (row.columns != 3) {
          throw new IOException("FILE target rows take exactly three columns");
        }
        Path raw = Path.of(row.value);
        if (!raw.isAbsolute()) {
          throw new IOException("FILE target must be an absolute path");
        }
        Path canonical = PathBinding.canonical(raw, true);
        PathBinding.requireRegularFile(canonical);
        if (!PathBinding.contains(projectRoot, canonical)) {
          throw new IOException("FILE target must be inside the registered project root");
        }
        registry.register(row.handle, IdeaTargetRegistry.Kind.FILE, canonical.toString(), projectRoot.toString());
        return;
      }
      case "RUN_CONFIGURATION": {
        if (row.columns != 3) {
          throw new IOException("RUN_CONFIGURATION target rows take exactly three columns");
        }
        registry.register(
            row.handle, IdeaTargetRegistry.Kind.RUN_CONFIGURATION, row.value, projectRoot.toString());
        return;
      }
      case "TEST_RESULT": {
        if (row.columns != 4 || row.detail.isEmpty()) {
          throw new IOException(
              "TEST_RESULT target rows must bind a tool window AND an exact existing content display name");
        }
        registry.register(
            row.handle,
            IdeaTargetRegistry.Kind.TEST_RESULT,
            row.value,
            row.detail,
            projectRoot.toString());
        return;
      }
      default:
        throw new IOException("unknown target kind rejected");
    }
  }

  private static String requireScalar(Map<String, String> scalars, String key) throws IOException {
    String value = scalars.get(key);
    if (value == null || value.isEmpty()) {
      throw new IOException("missing required configuration key: " + key);
    }
    return value;
  }

  private static long resolveTimeout(String raw) throws IOException {
    if (raw == null || raw.isEmpty()) {
      return DEFAULT_UI_TIMEOUT_MS;
    }
    long value;
    try {
      value = Long.parseLong(raw);
    } catch (NumberFormatException e) {
      throw new IOException("uiTimeoutMs must be an integer");
    }
    if (value < MIN_UI_TIMEOUT_MS || value > MAX_UI_TIMEOUT_MS) {
      throw new IOException("uiTimeoutMs outside the permitted range");
    }
    return value;
  }

  private static byte[] resolveSecret(String secretFile, Map<String, String> env) throws IOException {
    String fromEnv = env == null ? null : env.get(ENV_SECRET);
    if (fromEnv != null && fromEnv.trim().length() >= 32) {
      return fromEnv.trim().getBytes(StandardCharsets.UTF_8);
    }
    if (secretFile != null && !secretFile.isEmpty()) {
      Path path = Path.of(secretFile);
      if (Files.isSymbolicLink(path)) {
        throw new IOException("secret file must not be a symlink");
      }
      Path canonical = PathBinding.canonical(path, true);
      PathBinding.requireRegularFile(canonical);
      String value = new String(Files.readAllBytes(canonical), StandardCharsets.UTF_8).trim();
      if (value.length() >= 32) {
        return value.getBytes(StandardCharsets.UTF_8);
      }
    }
    return new byte[0];
  }

  private static List<String> decodeStrictly(byte[] bytes) throws IOException {
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (CharacterCodingException e) {
      throw new IOException("configuration file must be valid UTF-8");
    }
    List<String> lines = new ArrayList<>();
    int start = 0;
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        lines.add(text.substring(start, i));
        start = i + 1;
      }
    }
    if (start < text.length()) {
      lines.add(text.substring(start));
    }
    return lines;
  }

  private static String stripCarriageReturn(String line) {
    return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
  }

  private static final class Row {
    final String handle;
    final String kind;
    final String value;
    final String detail;
    final int columns;

    Row(String handle, String kind, String value, String detail, int columns) {
      this.handle = handle;
      this.kind = kind;
      this.value = value;
      this.detail = detail;
      this.columns = columns;
    }
  }
}
