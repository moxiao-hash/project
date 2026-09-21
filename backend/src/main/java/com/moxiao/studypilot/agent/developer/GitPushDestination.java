package com.moxiao.studypilot.agent.developer;

import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * 一次受治理推送所绑定的**不可变**目标。
 *
 * <p>对象一旦创建，其 {@link #uri()}、{@link #canonical()} 与 {@link #digest()} 都不再随
 * {@code .git/config} 变化；{@code push} 只允许针对该 URI 执行，绝不再按远端名重新解析配置。</p>
 *
 * <p>目标派生规则与 JGit 的 PUSH 语义一致：{@code Transport.openAll(..., Operation.PUSH)} 使用
 * {@code RemoteConfig.getPushURIs()}（{@code remote.<name>.pushurl}），仅在 pushurl 为空时回退
 * {@code getURIs()}（{@code remote.<name>.url}）。{@code RemoteConfig} 构造函数本身已经应用
 * {@code url.*.insteadOf} / {@code pushInsteadOf} 重写，因此这里拿到的是**重写后的有效目标**。</p>
 *
 * <p>规范化形式刻意保留用户名而剔除密码：SSH/SCP 形式下 {@code alice@host:repo.git} 与
 * {@code bob@host:repo.git} 可能解析到不同账号、不同授权与不同仓库，属于不同目标；而密码不参与
 * 目标判定，保留它只会让凭据轮换被误判成目标漂移，并有泄漏风险。</p>
 *
 * <p>{@link #toString()} 只输出摘要，绝不输出 URI，避免任何日志或错误信息带出凭据。</p>
 */
final class GitPushDestination {

    private static final String REMOTE_NAME = "origin";

    /** JGit 用 {@code null} scheme 表示 SCP 形式（{@code user@host:path}）。 */
    private static final String SCP_SCHEME = "scp";

    private final URIish uri;
    private final String canonical;
    private final String digest;

    private GitPushDestination(URIish uri, String canonical, String digest) {
        this.uri = uri;
        this.canonical = canonical;
        this.digest = digest;
    }

    /**
     * 从当前仓库配置派生唯一有效推送目标。
     *
     * @throws IllegalArgumentException 未配置 origin、有效目标不唯一，或声明了捕获目标后无法保留的
     *                                  远端选项（{@code receivepack}）
     */
    static GitPushDestination resolve(Config config) {
        RemoteConfig remote;
        try {
            remote = new RemoteConfig(config, REMOTE_NAME);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Git 仓库 origin 地址无效");
        }
        // JGit 只在按远端名解析时通过 Transport.applyConfig 应用 receivepack。
        // 捕获目标后该设置无法保留，宁可失败关闭，也不静默换成默认的 git-receive-pack。
        if (config.getString("remote", REMOTE_NAME, "receivepack") != null) {
            throw new IllegalArgumentException(
                    "origin 配置了 receivepack，受治理推送在捕获目标后无法保留该设置，已拒绝执行");
        }
        List<URIish> pushUris = remote.getPushURIs();
        List<URIish> effective = pushUris.isEmpty() ? remote.getURIs() : pushUris;
        if (effective.isEmpty()) {
            throw new IllegalArgumentException("Git 仓库未配置 origin");
        }
        if (effective.size() != 1) {
            throw new IllegalArgumentException(
                    "Git push 目标必须是唯一确定的一个远端地址，请移除多余的 url 或 pushurl");
        }
        URIish uri = effective.get(0);
        String canonical = canonicalize(uri);
        return new GitPushDestination(uri, canonical, sha256(canonical));
    }

    /**
     * 规范化表示：{@code scheme://user@host[:effectivePort]path}。
     *
     * <p>显式默认端口与省略端口视为同一目标（例如 {@code ssh://host/x} 与 {@code ssh://host:22/x}），
     * 避免等价写法造成误报；SCP 形式与 {@code ssh://} 形式保持区分，因为两者的 path 语义不同
     * （SCP 为相对登录目录，{@code ssh://} 为绝对路径）。</p>
     */
    static String canonicalize(URIish uri) {
        String scheme = uri.getScheme() == null
                ? SCP_SCHEME : uri.getScheme().toLowerCase(Locale.ROOT);
        String user = uri.getUser() == null ? "" : uri.getUser();
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null ? "" : uri.getPath();
        int port = effectivePort(scheme, uri.getPort());
        return scheme + "://" + user + "@" + host + (port > 0 ? ":" + port : "") + path;
    }

    private static int effectivePort(String scheme, int port) {
        if (port > 0) return port;
        return switch (scheme) {
            case "ssh" -> 22;
            case "http" -> 80;
            case "https" -> 443;
            case "git" -> 9418;
            default -> -1;
        };
    }

    URIish uri() {
        return uri;
    }

    String canonical() {
        return canonical;
    }

    String digest() {
        return digest;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    /** 只暴露摘要：URI 可能带有凭据，绝不进入日志或错误信息。 */
    @Override
    public String toString() {
        return "GitPushDestination[digest=" + digest + "]";
    }
}
