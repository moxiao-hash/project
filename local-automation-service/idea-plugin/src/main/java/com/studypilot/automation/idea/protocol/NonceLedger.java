package com.studypilot.automation.idea.protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Persistent replay protection for the plugin socket.
 *
 * Consumed nonces are appended to a ledger file and flushed to disk BEFORE any UI effect, so
 * a plugin restart replays nothing. The ledger is owner-only (0600) and prunes entries whose
 * expiry plus a retention window has passed.
 */
public final class NonceLedger {

  private static final long DEFAULT_RETENTION_MS = 24L * 60 * 60 * 1000;
  private static final int PRUNE_THRESHOLD = 20_000;

  private final Path ledgerFile;
  private final long retentionMs;
  private final Map<String, Long> consumed = new LinkedHashMap<>();

  public NonceLedger(Path ledgerFile) {
    this(ledgerFile, DEFAULT_RETENTION_MS);
  }

  public NonceLedger(Path ledgerFile, long retentionMs) {
    this.ledgerFile = ledgerFile;
    this.retentionMs = retentionMs;
    load();
  }

  private void load() {
    if (ledgerFile == null || !Files.exists(ledgerFile)) {
      return;
    }
    try (BufferedReader reader = Files.newBufferedReader(ledgerFile, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        int tab = line.indexOf('\t');
        if (tab <= 0) {
          continue;
        }
        try {
          long expiry = Long.parseLong(line.substring(0, tab));
          consumed.put(line.substring(tab + 1), expiry);
        } catch (NumberFormatException ignored) {
          // A corrupt line is ignored rather than trusted.
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Atomically records the nonce. Returns false when it was already consumed (replay). */
  public synchronized boolean consume(String nonce, long expiresAtMs) {
    if (nonce == null || nonce.isEmpty()) {
      return false;
    }
    pruneIfNeeded(expiresAtMs);
    if (consumed.containsKey(nonce)) {
      return false;
    }
    appendToLedger(nonce, expiresAtMs);
    consumed.put(nonce, expiresAtMs);
    return true;
  }

  private void appendToLedger(String nonce, long expiresAtMs) {
    if (ledgerFile == null) {
      return;
    }
    try {
      Path parent = ledgerFile.getParent();
      if (parent != null && !Files.exists(parent)) {
        Files.createDirectories(parent);
        setOwnerOnlyDirectory(parent);
      }
      if (!Files.exists(ledgerFile)) {
        Files.createFile(ledgerFile);
        setOwnerOnlyFile(ledgerFile);
      }
      String line = expiresAtMs + "\t" + nonce + "\n";
      Files.write(
          ledgerFile,
          line.getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.WRITE,
          StandardOpenOption.APPEND);
      try (java.nio.channels.FileChannel channel =
          java.nio.channels.FileChannel.open(ledgerFile, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void pruneIfNeeded(long nowMs) {
    if (consumed.size() < PRUNE_THRESHOLD) {
      return;
    }
    Map<String, Long> kept = new LinkedHashMap<>();
    for (Map.Entry<String, Long> entry : consumed.entrySet()) {
      if (entry.getValue() + retentionMs > nowMs) {
        kept.put(entry.getKey(), entry.getValue());
      }
    }
    consumed.clear();
    consumed.putAll(kept);
  }

  public synchronized int size() {
    return consumed.size();
  }

  public static void setOwnerOnlyFile(Path path) {
    setPermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
  }

  public static void setOwnerOnlyDirectory(Path path) {
    setPermissions(
        path,
        EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE));
  }

  private static void setPermissions(Path path, Set<PosixFilePermission> permissions) {
    try {
      Files.setPosixFilePermissions(path, permissions);
    } catch (UnsupportedOperationException | IOException ignored) {
      // Non-POSIX filesystems cannot express this; the socket permission check still applies.
    }
  }
}
