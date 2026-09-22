package com.studypilot.automation.idea.platform;

import com.studypilot.automation.idea.dispatch.IdeActionDispatcher;
import com.studypilot.automation.idea.protocol.NonceLedger;
import com.studypilot.automation.idea.protocol.PluginProtocol;
import com.studypilot.automation.idea.protocol.PluginRequest;
import com.studypilot.automation.idea.protocol.PluginResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The plugin's PRIVATE Unix Domain Socket.
 *
 * Only the local automation service connects to it. It is a separate socket, key, nonce
 * ledger and protocol domain from the Java-facing socket; the plugin never connects to or
 * listens on the Java-facing socket, and never opens a TCP or HTTP listener (only
 * {@link StandardProtocolFamily#UNIX} is used).
 *
 * Hardening: parent directory must not be writable by other users, the socket file is
 * forced to 0600, frames are single-line UTF-8 JSON capped at 16 KiB, and every request is
 * authenticated, replayed-protected and dispatched with a read deadline.
 */
public final class PluginSocketServer {

  private static final long READ_DEADLINE_MS = 2_000L;
  private static final int READ_POLL_MS = 5;

  private final PluginConfig config;
  private final PluginProtocol protocol;
  private final IdeActionDispatcher dispatcher;
  private final Consumer<String> log;

  private volatile boolean running;
  private ServerSocketChannel serverChannel;
  private Thread acceptThread;

  public PluginSocketServer(
      PluginConfig config, PluginProtocol protocol, IdeActionDispatcher dispatcher, Consumer<String> log) {
    this.config = config;
    this.protocol = protocol;
    this.dispatcher = dispatcher;
    this.log = log == null ? message -> {} : log;
  }

  public synchronized void start() throws IOException {
    if (running) {
      return;
    }
    Path socketPath = config.socketPath;
    Path parent = socketPath.getParent();
    if (parent == null) {
      throw new IOException("socket path must have a parent directory");
    }
    if (!Files.exists(parent)) {
      Files.createDirectories(parent);
      NonceLedger.setOwnerOnlyDirectory(parent);
    }
    if (Files.isSymbolicLink(parent)) {
      throw new IOException("socket parent directory must not be a symlink");
    }
    requireNotOtherWritable(parent);

    if (Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS)) {
      if (Files.isSymbolicLink(socketPath)) {
        throw new IOException("refusing to replace a symlinked socket path");
      }
      Files.delete(socketPath);
    }

    serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    serverChannel.bind(UnixDomainSocketAddress.of(socketPath), 16);
    Files.setPosixFilePermissions(
        socketPath, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    requireOwnerOnly(socketPath);

    running = true;
    acceptThread = new Thread(this::acceptLoop, "studypilot-plugin-uds");
    acceptThread.setDaemon(true);
    acceptThread.start();
    log.accept("plugin socket listening (owner-only unix domain socket)");
  }

  public synchronized void stop() {
    running = false;
    try {
      if (serverChannel != null && serverChannel.isOpen()) {
        serverChannel.close();
      }
    } catch (IOException ignored) {
      // shutting down
    }
    serverChannel = null;
    if (acceptThread != null) {
      acceptThread.interrupt();
      acceptThread = null;
    }
    try {
      Files.deleteIfExists(config.socketPath);
    } catch (IOException ignored) {
      // best effort
    }
  }

  public boolean isRunning() {
    return running;
  }

  private void acceptLoop() {
    while (running) {
      try {
        SocketChannel client = serverChannel == null ? null : serverChannel.accept();
        if (client == null) {
          continue;
        }
        try {
          handle(client);
        } finally {
          try {
            client.close();
          } catch (IOException ignored) {
            // client already gone
          }
        }
      } catch (IOException e) {
        if (running) {
          log.accept("plugin socket accept failed");
        }
      }
    }
  }

  private void handle(SocketChannel client) throws IOException {
    byte[] frame = readFrame(client);
    if (frame == null) {
      writeFrame(client, rejectFrame("INVALID_FRAME", "no complete single-line frame was received"));
      return;
    }
    if (frame.length == 0) {
      writeFrame(client, rejectFrame("INVALID_FRAME", "empty request frame"));
      return;
    }

    PluginProtocol.Result<PluginRequest> parsed = protocol.parse(frame);
    if (!parsed.ok) {
      writeFrame(client, rejectFrame(parsed.errorCode, parsed.message));
      return;
    }

    PluginResponse response = dispatcher.dispatch(parsed.value, System.currentTimeMillis());
    writeFrame(client, protocol.format(response));
  }

  /** Reads one newline-terminated frame with a deadline. Returns null on timeout/EOF. */
  private byte[] readFrame(SocketChannel client) throws IOException {
    client.configureBlocking(false);
    ByteArrayOutputStream buffer = new ByteArrayOutputStream(512);
    ByteBuffer chunk = ByteBuffer.allocate(4096);
    long deadline = System.currentTimeMillis() + READ_DEADLINE_MS;
    while (System.currentTimeMillis() < deadline) {
      chunk.clear();
      int read = client.read(chunk);
      if (read > 0) {
        chunk.flip();
        byte[] bytes = new byte[chunk.remaining()];
        chunk.get(bytes);
        buffer.write(bytes);
        if (buffer.size() > PluginProtocol.MAX_FRAME_BYTES) {
          client.configureBlocking(true);
          return new byte[PluginProtocol.MAX_FRAME_BYTES + 1];
        }
        byte[] current = buffer.toByteArray();
        if (indexOfNewline(current) >= 0) {
          client.configureBlocking(true);
          byte[] line = new byte[indexOfNewline(current)];
          System.arraycopy(current, 0, line, 0, line.length);
          return line;
        }
      } else if (read < 0) {
        client.configureBlocking(true);
        return null;
      } else {
        try {
          Thread.sleep(READ_POLL_MS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return null;
        }
      }
    }
    client.configureBlocking(true);
    return null;
  }

  private static int indexOfNewline(byte[] bytes) {
    for (int i = 0; i < bytes.length; i++) {
      if (bytes[i] == '\n') {
        return i;
      }
    }
    return -1;
  }

  private void writeFrame(SocketChannel client, String frame) throws IOException {
    ByteBuffer out = ByteBuffer.wrap(frame.getBytes(StandardCharsets.UTF_8));
    while (out.hasRemaining()) {
      client.write(out);
    }
  }

  private String rejectFrame(String code, String message) {
    PluginResponse response =
        PluginResponse.rejected(null, null, code, message, java.time.Instant.now().toString());
    return protocol.format(response);
  }

  private static void requireNotOtherWritable(Path directory) throws IOException {
    Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(directory);
    if (permissions.contains(PosixFilePermission.GROUP_WRITE)
        || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
      throw new IOException("socket parent directory must not be writable by other users");
    }
  }

  private static void requireOwnerOnly(Path socketPath) throws IOException {
    Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(socketPath);
    if (permissions.contains(PosixFilePermission.GROUP_READ)
        || permissions.contains(PosixFilePermission.GROUP_WRITE)
        || permissions.contains(PosixFilePermission.OTHERS_READ)
        || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
      throw new IOException("plugin socket file must be owner-only");
    }
  }
}
