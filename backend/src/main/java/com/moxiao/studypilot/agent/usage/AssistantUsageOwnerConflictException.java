package com.moxiao.studypilot.agent.usage;

/**
 * 幂等键（{@code usageId} / {@code reservationId}）已归属于另一个 owner。
 *
 * <p>{@code usageId} 是全局幂等键，但它由调用方提供，不能假设它天然属于当前 owner。
 * 一旦发现冲突，服务端必须拒绝这次写操作，而不是顺手改写别人的预占行：后者会让 A 的上报
 * 终结或释放 B 的许可。</p>
 *
 * <p>{@link #code()} 是稳定的机器可读标识，内部接口以 HTTP 409 返回它。</p>
 */
public class AssistantUsageOwnerConflictException extends RuntimeException {

    public static final String CODE = "ASSISTANT_USAGE_OWNER_CONFLICT";

    private final String usageId;

    public AssistantUsageOwnerConflictException(String usageId) {
        // 只带幂等键本身，不带提示词、正文或其他 owner 的数据。
        super("幂等键已归属于其他 owner，拒绝跨 owner 变更");
        this.usageId = usageId;
    }

    public String code() {
        return CODE;
    }

    public String getUsageId() {
        return usageId;
    }
}
