package com.moxiao.studypilot.roadmap.api;

import com.moxiao.studypilot.roadmap.domain.RoadmapScheduleItemStatus;

import java.time.LocalDate;
import java.util.List;

public record RoadmapScheduleResponse(
        String scheduleId,
        String timeZone,
        int dailyCapacityMinutes,
        boolean weekendsEnabled,
        List<Day> days
) {
    public record Day(LocalDate date, int plannedMinutes, List<Item> items) { }

    public record Item(
            String id,
            String nodeId,
            String nodeCode,
            String title,
            int plannedMinutes,
            RoadmapScheduleItemStatus status
    ) { }
}
