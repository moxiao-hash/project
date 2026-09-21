package com.moxiao.studypilot.agent.developer;

/**
 * Git push 预览：把确认所绑定的全部事实一次性摊开。
 *
 * <p>确认时服务端会重新读取这些事实并逐项比对，任何漂移都失败关闭，避免"预览的是 A 远端、
 * 推的是 B 远端"。</p>
 *
 * <p>{@code expectedRemoteRefCommit} 是 {@code expectedRemoteRef} 在预览时解析出的提交；
 * 远端跟踪 ref 不存在时为空串。只绑定 ref 名称是恒定的常量、无法发现远端前移，因此必须绑定
 * 解析后的提交。</p>
 *
 * <p>{@code remoteUrlDigest} 绑定的是 JGit **实际 PUSH 使用的唯一目标地址**的摘要
 * （{@code remote.origin.pushurl} 优先，为空时回退 {@code remote.origin.url}），而不是 fetch URL；
 * 只绑定 fetch URL 会允许 pushurl 把已确认的推送改到未绑定的目标。</p>
 */
public record GitPushPreview(
        String workspaceId,
        String remoteName,
        String remoteUrlDigest,
        String branch,
        String expectedHead,
        String expectedRemoteRef,
        String expectedRemoteRefCommit,
        int aheadCount,
        int timeoutSeconds
) { }
