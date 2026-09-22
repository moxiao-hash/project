package com.moxiao.studypilot.agent.developer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Task 33 测试替身：最小 Unix Domain Socket 对端。
 *
 * <p>只实现冻结契约的传输层（单行 UTF-8 JSON、换行结束、有界），用于在 ZCode 的真实
 * 本地服务就绪前验证 Java 客户端的签名、framing、超时、权限与响应关联行为。
 * 它不是生产代码，也不引入任何通用界面能力。</p>
 */
final class LocalAutomationStubServer implements AutoCloseable {

    private static final int READ_LIMIT = 64 * 1024;

    private final Path socketPath;
    private final ServerSocketChannel server;
    private final Thread thread;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final List<String> received = new CopyOnWriteArrayList<>();
    private final List<byte[]> receivedRaw = new CopyOnWriteArrayList<>();
    private final List<Boolean> newlineTerminated = new CopyOnWriteArrayList<>();
    private final CountDownLatch connections;
    private final Function<String, byte[]> responder;
    private final boolean respond;
    private final int expectedConnections;

    private volatile IOException failure;

    private LocalAutomationStubServer(
            Path socketPath,
            Function<String, byte[]> responder,
            boolean respond,
            Set<PosixFilePermission> permissions,
            int expectedConnections
    ) throws IOException {
        this.socketPath = socketPath;
        this.responder = responder;
        this.respond = respond;
        this.expectedConnections = expectedConnections;
        this.connections = new CountDownLatch(expectedConnections);
        this.server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        if (Files.exists(socketPath)) {
            Files.delete(socketPath);
        }
        server.bind(UnixDomainSocketAddress.of(socketPath));
        try {
            Files.setPosixFilePermissions(socketPath, permissions);
        } catch (UnsupportedOperationException exception) {
            // 非 POSIX 平台：权限断言由调用方决定是否跳过。
        }
        this.thread = new Thread(this::serve, "local-automation-stub");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    static LocalAutomationStubServer ownerOnly(Path socketPath, Function<String, byte[]> responder) {
        return ownerOnly(socketPath, responder, 1);
    }

    static LocalAutomationStubServer ownerOnly(
            Path socketPath, Function<String, byte[]> responder, int expectedConnections
    ) {
        try {
            return new LocalAutomationStubServer(socketPath, responder, true,
                    ownerOnlyPermissions(), expectedConnections);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static LocalAutomationStubServer worldAccessible(Path socketPath) {
        try {
            return new LocalAutomationStubServer(socketPath, request -> new byte[0], true,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
                            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE), 1);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** 接受连接但永不响应，用于验证读超时失败关闭。 */
    static LocalAutomationStubServer silent(Path socketPath) {
        try {
            return new LocalAutomationStubServer(socketPath, request -> new byte[0], false,
                    ownerOnlyPermissions(), 1);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Set<PosixFilePermission> ownerOnlyPermissions() {
        return Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    Path socketPath() {
        return socketPath;
    }

    List<String> received() {
        return List.copyOf(received);
    }

    List<byte[]> receivedRaw() {
        return List.copyOf(receivedRaw);
    }

    boolean wasNewlineTerminated(int index) {
        return newlineTerminated.get(index);
    }

    boolean awaitConnections(long millis) throws InterruptedException {
        return connections.await(millis, TimeUnit.MILLISECONDS);
    }

    IOException failure() {
        return failure;
    }

    private void serve() {
        while (running.get()) {
            try (SocketChannel channel = server.accept()) {
                connections.countDown();
                Frame frame = readFrame(channel);
                if (frame == null) {
                    continue;
                }
                receivedRaw.add(frame.body());
                newlineTerminated.add(frame.newlineTerminated());
                received.add(new String(frame.body(), StandardCharsets.UTF_8));
                if (!respond) {
                    while (running.get() && channel.isOpen()) {
                        Thread.sleep(20);
                    }
                    continue;
                }
                byte[] body = responder.apply(new String(frame.body(), StandardCharsets.UTF_8));
                ByteArrayOutputStream wire = new ByteArrayOutputStream();
                wire.write(body);
                wire.write('\n');
                writeFully(channel, wire.toByteArray());
            } catch (IOException exception) {
                if (running.get()) {
                    failure = exception;
                    return;
                }
                return;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void writeFully(SocketChannel channel, byte[] payload) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private record Frame(byte[] body, boolean newlineTerminated) { }

    private Frame readFrame(SocketChannel channel) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ByteBuffer single = ByteBuffer.allocate(1);
        while (buffer.size() <= READ_LIMIT) {
            single.clear();
            int read = channel.read(single);
            if (read < 0) {
                return buffer.size() == 0 ? null
                        : new Frame(buffer.toByteArray(), false);
            }
            if (single.get(0) == '\n') {
                return new Frame(buffer.toByteArray(), true);
            }
            buffer.write(single.get(0));
        }
        return new Frame(buffer.toByteArray(), false);
    }

    @Override
    public void close() {
        running.set(false);
        try {
            server.close();
        } catch (IOException ignored) {
            // 关闭失败不影响测试结论。
        }
        thread.interrupt();
        try {
            Files.deleteIfExists(socketPath);
        } catch (IOException ignored) {
            // 临时目录由 JUnit 清理。
        }
    }
}
