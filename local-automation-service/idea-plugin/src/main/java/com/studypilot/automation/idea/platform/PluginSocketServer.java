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
import java.nio.file.attribute.BasicFileAttributes;
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
 * Hardening:
 *   * the parent directory must exist without symlinked components and must not be writable by
 *     other users;
 *   * a pre-existing object at the socket path is accepted only when it really is a socket —
 *     a regular file or directory is refused and never deleted;
 *   * the socket file is forced to 0600;
 *   * a request must be EXACTLY ONE newline-terminated single-line UTF-8 JSON frame capped at
 *     16 KiB. Trailing data after the newline (a second frame, or any extra bytes) is rejected
 *     as INVALID_FRAME before any effect.
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
  /** True only once WE bound the socket file, so stop() never deletes a foreign object. */
  private boolean boundSocketFile;

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
    if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
      Files.createDirectories(parent);
      NonceLedger.setOwnerOnlyDirectory(parent);
    }
    // No component of the socket path may be a symlink, and the parent must be owner-only.
    PathBinding.rejectSymlinkComponents(parent);
    PathBinding.requireDirectory(parent);
    requireNotOtherWritable(parent);
    PathBinding.rejectSymlinkComponents(socketPath);

    if (Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS)) {
      if (Files.isSymbolicLink(socketPath)) {
        throw new IOException("refusing to replace a symlinked socket path");
      }
      BasicFileAttributes attributes =
          Files.readAttributes(socketPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!attributes.isOther()) {
        // A regular file or directory is never deleted; the operator must resolve it.
        throw new IOException("refusing to replace a non-socket object at the socket path");
      }
      Files.delete(socketPath);
    }

    serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    serverChannel.bind(UnixDomainSocketAddress.of(socketPath), 16);
    boundSocketFile = true;
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
    if (boundSocketFile) {
      try {
        Files.deleteIfExists(config.socketPath);
      } catch (IOException ignored) {
        // best effort
      }
      boundSocketFile = false;
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
    FrameRead frame = readFrame(client);
    if (frame.errorCode != null) {
      writeFrame(client, rejectFrame(frame.errorCode, frame.errorMessage));
      return;
    }
    if (frame.bytes == null || frame.bytes.length == 0) {
      writeFrame(client, rejectFrame("INVALID_FRAME", "no complete single-line frame was received"));
      return;
    }

    PluginProtocol.Result<PluginRequest> parsed = protocol.parse(frame.bytes);
    if (!parsed.ok) {
      writeFrame(client, rejectFrame(parsed.errorCode, parsed.message));
      return;
    }

    PluginResponse response = dispatcher.dispatch(parsed.value, System.currentTimeMillis());
    writeFrame(client, protocol.format(response));
  }

  private static final class FrameRead {
    final byte[] bytes;
    final String errorCode;
    final String errorMessage;

    private FrameRead(byte[] bytes, String errorCode, String errorMessage) {
      this.bytes = bytes;
      this.errorCode = errorCode;
      this.errorMessage = errorMessage;
    }

    static FrameRead ok(byte[] bytes) {
      return new FrameRead(bytes, null, null);
    }

    static FrameRead error(String code, String message) {
      return new FrameRead(null, code, message);
    }
  }

  /**
   * Reads exactly one newline-terminated frame with a deadline.
   *
   * Any byte after the terminating newline — including a second JSON frame delivered in the
   * same write — makes the request invalid, so a caller can never smuggle extra operations
   * past the single-frame contract.
   */
  private FrameRead readFrame(SocketChannel client) throws IOException {
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
          return FrameRead.error("OVERSIZE_FRAME", "frame exceeds 16 KiB");
        }
        byte[] current = buffer.toByteArray();
        int newline = indexOfNewline(current);
        if (newline >= 0) {
          if (newline != current.length - 1) {
            client.configureBlocking(true);
            return FrameRead.error("INVALID_FRAME", "request must be exactly one single-line frame");
          }
          // A second frame may already be pending in the socket buffer; drain it defensively.
          ByteBuffer extra = ByteBuffer.allocate(1);
          if (client.read(extra) > 0) {
            client.configureBlocking(true);
            return FrameRead.error("INVALID_FRAME", "request must be exactly one single-line frame");
          }
          client.configureBlocking(true);
          byte[] line = new byte[newline];
          System.arraycopy(current, 0, line, 0, newline);
          return FrameRead.ok(line);
        }
      } else if (read < 0) {
        client.configureBlocking(true);
        return FrameRead.error("INVALID_FRAME", "connection closed before a complete frame arrived");
      } else {
        try {
          Thread.sleep(READ_POLL_MS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return FrameRead.error("INVALID_FRAME", "interrupted while reading the frame");
        }
      }
    }
    client.configureBlocking(true);
    return FrameRead.error("INVALID_FRAME", "no complete single-line frame was received");
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
