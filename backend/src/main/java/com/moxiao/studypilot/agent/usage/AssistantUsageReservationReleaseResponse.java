package com.moxiao.studypilot.agent.usage;

/** 释放预占的结果；重复释放返回 {@code released=false}，不视为错误。 */
public record AssistantUsageReservationReleaseResponse(String reservationId, boolean released) {
}
