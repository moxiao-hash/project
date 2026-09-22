package com.studypilot.automation.idea.platform;

import com.studypilot.automation.idea.registry.IdeaTargetRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Trusted, plugin-side configuration.
 *
 * Loaded from a tab-separated file that only the local operator can write, plus the HMAC key
 * injected through the environment (or an owner-only file). The configuration carries the
 * plugin's OWN target registry: the socket request only ever names an opaque handle.
 *
 * Never logged, never echoed in a response.
 */
public final class PluginConfig {

  public static final String ENV_SECRET = "STUDYPILOT_IDEA_PLUGIN_HMAC_SECRET";

  public final Path socketPath;
  public final Path ledgerPath;
  public final Path projectRoot;
  public final byte[] secret;
  public final long uiTimeoutMs;
  public final IdeaTargetRegistry registry;

  private PluginConfig(
      Path socketPath,
      Path ledgerPath,
      Path projectRoot,
      byte[] secret,
      long uiTimeoutMs,
      IdeaTargetRegistry registry) {
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
    if (!Files.isRegularFile(configFile)) {
      throw new IOException("plugin configuration file is missing");
    }
    if (Files.isSymbolicLink(configFile)) {
      throw new IOException("plugin configuration file must not be a symlink");
    }

    Map<String, String> scalars = new HashMap<>();
    IdeaTargetRegistry registry = new IdeaTargetRegistry();
    String projectRootValue = null;

    List<String> lines = Files.readAllLines(configFile, StandardCharsets.UTF_8);
    for (String raw : lines) {
      String line = raw.trim();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      String[] parts = raw.split("\t", -1);
      if (parts.length == 2) {
        scalars.put(parts[0].trim(), parts[1].trim());
        continue;
      }
      if (parts.length == 3) {
        registry.register(parts[0].trim(), kindOf(parts[1].trim()), parts[2].trim(), requireProjectRoot(projectRootValue));
        continue;
      }
      throw new IOException("invalid configuration line");
    }

    projectRootValue = scalars.get("projectRoot");
    if (projectRootValue == null || projectRootValue.isEmpty()) {
      throw new IOException("projectRoot is required");
    }

    // Re-register now that the project root is known, validating it strictly.
    IdeaTargetRegistry validated = new IdeaTargetRegistry();
    for (String raw : lines) {
      String line = raw.trim();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      String[] parts = raw.split("\t", -1);
      if (parts.length == 3) {
        validated.register(parts[0].trim(), kindOf(parts[1].trim()), parts[2].trim(), projectRootValue);
      }
    }

    Path socketPath = requireAbsolute(scalars.get("socketPath"), "socketPath");
    Path ledgerPath = requireAbsolute(scalars.get("ledgerPath"), "ledgerPath");
    Path projectRoot = requireAbsolute(projectRootValue, "projectRoot");

    long uiTimeoutMs = 2000L;
    String timeoutValue = scalars.get("uiTimeoutMs");
    if (timeoutValue != null && !timeoutValue.isEmpty()) {
      try {
        uiTimeoutMs = Long.parseLong(timeoutValue);
      } catch (NumberFormatException e) {
        throw new IOException("uiTimeoutMs must be an integer");
      }
      if (uiTimeoutMs < 250L || uiTimeoutMs > 30_000L) {
        throw new IOException("uiTimeoutMs must be between 250 and 30000");
      }
    }

    byte[] secret = resolveSecret(scalars.get("secretFile"), env);
    if (secret.length < 32) {
      throw new IOException("plugin signing key must be at least 32 bytes");
    }

    return new PluginConfig(socketPath, ledgerPath, projectRoot, secret, uiTimeoutMs, validated);
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
      String value = Files.readString(path, StandardCharsets.UTF_8).trim();
      if (value.length() >= 32) {
        return value.getBytes(StandardCharsets.UTF_8);
      }
    }
    return new byte[0];
  }

  private static String requireProjectRoot(String value) throws IOException {
    if (value == null || value.isEmpty()) {
      throw new IOException("projectRoot must be declared before target rows");
    }
    return value;
  }

  private static Path requireAbsolute(String value, String name) throws IOException {
    if (value == null || value.isEmpty() || !value.startsWith("/")) {
      throw new IOException(name + " must be an absolute path");
    }
    return Path.of(value);
  }

  private static IdeaTargetRegistry.Kind kindOf(String raw) throws IOException {
    switch (raw) {
      case "FILE":
        return IdeaTargetRegistry.Kind.FILE;
      case "RUN_CONFIGURATION":
        return IdeaTargetRegistry.Kind.RUN_CONFIGURATION;
      case "TEST_RESULT":
        return IdeaTargetRegistry.Kind.TEST_RESULT;
      default:
        throw new IOException("unknown target kind");
    }
  }
}
