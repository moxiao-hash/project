package com.moxiao.studypilot.agent.developer;

/** Git push 确认请求；必须回传预览绑定的全部事实，缺一不可。 */
public record GitPushRequest(
        String workspaceId,
        String remoteName,
        String branch,
        String expectedHead,
        String remoteUrlDigest,
        String expectedRemoteRef,
        String expectedRemoteRefCommit,
        int timeoutSeconds
) { }
