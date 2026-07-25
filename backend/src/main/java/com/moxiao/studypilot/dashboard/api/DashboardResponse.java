package com.moxiao.studypilot.dashboard.api;

public record DashboardResponse(
        long activeGoalCount,
        long todayTaskCount,
        long completedTodayTaskCount,
        long pendingMaterialCount,
        long lowMasteryCount,
        long unreadNotificationCount
) {
}
