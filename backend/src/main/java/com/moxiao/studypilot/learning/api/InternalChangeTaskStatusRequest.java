package com.moxiao.studypilot.learning.api;

import com.moxiao.studypilot.learning.domain.LearningTaskStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Agent 修改任务状态时使用的严格内部契约。
 *
 * <p>expectedVersion 防止 Agent 根据旧快照覆盖用户的新操作；idempotencyKey
 * 防止网络重试把同一个操作执行多次。</p>
 */
public record InternalChangeTaskStatusRequest(
        @NotBlank String ownerId,
        @NotBlank @Size(max = 180) String idempotencyKey,
        @Min(1) int expectedVersion,
        @NotNull LearningTaskStatus status,
        LocalDate scheduledDate,
        @Size(max = 255) String reason
    ) {

    public InternalChangeTaskStatusRequest {
        idempotencyKey = idempotencyKey == null ? null : idempotencyKey.trim();
        reason = reason == null ? null : reason.trim();
        if (status == LearningTaskStatus.TODO) {
            throw new IllegalArgumentException("Agent 任务操作不支持把任务改回 TODO");
        }
        if (status == LearningTaskStatus.DEFERRED && scheduledDate == null) {
            throw new IllegalArgumentException("延期任务必须提供新的安排日期");
        }
        if (status != null
                && status != LearningTaskStatus.DEFERRED
                && scheduledDate != null) {
            throw new IllegalArgumentException("只有延期操作可以提供新的安排日期");
        }
        if ((status == LearningTaskStatus.SKIPPED
                || status == LearningTaskStatus.DEFERRED)
                && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("跳过或延期任务必须说明原因");
        }
    }

    public ChangeTaskStatusRequest toStatusRequest() {
        return new ChangeTaskStatusRequest(status, scheduledDate, reason);
    }
}
