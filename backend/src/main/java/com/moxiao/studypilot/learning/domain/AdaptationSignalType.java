package com.moxiao.studypilot.learning.domain;

/**
 * 由 Java 根据真实任务数据计算的计划偏差信号。
 */
public enum AdaptationSignalType {
    OVERDUE_TASKS,
    CONSECUTIVE_SKIPS,
    TIME_ESTIMATE_BIAS
}
