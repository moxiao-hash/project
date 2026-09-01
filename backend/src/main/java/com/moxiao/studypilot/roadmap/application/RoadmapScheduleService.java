package com.moxiao.studypilot.roadmap.application;

import com.moxiao.studypilot.roadmap.api.RoadmapScheduleResponse;
import com.moxiao.studypilot.roadmap.domain.CompletionStatus;
import com.moxiao.studypilot.roadmap.domain.RoadmapScheduleItemStatus;
import com.moxiao.studypilot.roadmap.domain.QuizStatus;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapScheduleItemEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapScheduleItemJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapScheduleStateEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapScheduleStateJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapEntity;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapNodeEntity;
import com.moxiao.studypilot.roadmap.infrastructure.UserRoadmapNodeJpaRepository;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import com.moxiao.studypilot.user.domain.WeekendPreference;
import com.moxiao.studypilot.user.infrastructure.UserSettingsJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RoadmapScheduleService {
    private static final String CURRENT = "CURRENT";
    private static final String DEFAULT_ZONE = "Asia/Shanghai";
    private static final int DEFAULT_CAPACITY = 60;

    private final UserRoadmapJpaRepository enrollmentRepository;
    private final UserRoadmapNodeJpaRepository stateRepository;
    private final RoadmapNodeJpaRepository nodeRepository;
    private final RoadmapScheduleStateJpaRepository scheduleRepository;
    private final RoadmapScheduleItemJpaRepository itemRepository;
    private final UserSettingsJpaRepository settingsRepository;

    public RoadmapScheduleService(
            UserRoadmapJpaRepository enrollmentRepository,
            UserRoadmapNodeJpaRepository stateRepository,
            RoadmapNodeJpaRepository nodeRepository,
            RoadmapScheduleStateJpaRepository scheduleRepository,
            RoadmapScheduleItemJpaRepository itemRepository,
            UserSettingsJpaRepository settingsRepository
    ) {
        this.enrollmentRepository = enrollmentRepository;
        this.stateRepository = stateRepository;
        this.nodeRepository = nodeRepository;
        this.scheduleRepository = scheduleRepository;
        this.itemRepository = itemRepository;
        this.settingsRepository = settingsRepository;
    }

    @Transactional
    public RoadmapScheduleResponse getOrCreate(String ownerId, LocalDate from, LocalDate to) {
        return refresh(ownerId, from, to);
    }

    @Transactional
    public RoadmapScheduleResponse refresh(String ownerId, LocalDate from, LocalDate to) {
        UserRoadmapEntity enrollment = enrollmentRepository
                .findByOwnerIdAndActiveSlotForUpdate(ownerId, CURRENT)
                .orElseThrow(() -> new ResourceNotFoundException("当前学习路线不存在"));
        Settings settings = settings(ownerId);
        LocalDate start = from == null ? LocalDate.now(ZoneId.of(settings.timeZone())) : from;
        LocalDate end = to == null ? start.plusDays(6) : to;
        if (!end.equals(start.plusDays(6))) {
            throw new IllegalArgumentException("路线日程必须是包含首尾日期的连续七天");
        }
        Instant now = Instant.now();
        RoadmapScheduleStateEntity schedule = scheduleRepository.findByOwnerId(ownerId)
                .orElseGet(() -> scheduleRepository.save(new RoadmapScheduleStateEntity(
                        UUID.randomUUID().toString(), ownerId, enrollment.getId(),
                        settings.timeZone(), settings.capacity(), settings.weekendsEnabled(), now)));
        List<RoadmapScheduleItemEntity> existing = itemRepository
                .findAllByOwnerIdAndUserRoadmapId(ownerId, enrollment.getId());
        Map<String, RoadmapScheduleItemEntity> byState = existing.stream().collect(
                Collectors.toMap(RoadmapScheduleItemEntity::getUserRoadmapNodeId, Function.identity()));
        Map<LocalDate, Integer> usedMinutes = new HashMap<>();
        for (RoadmapScheduleItemEntity item : existing) {
            if (item.getStatus() != RoadmapScheduleItemStatus.PLANNED
                    && !item.getScheduledDate().isBefore(start)
                    && !item.getScheduledDate().isAfter(end)) {
                usedMinutes.merge(item.getScheduledDate(), item.getPlannedMinutes(), Integer::sum);
            }
        }
        Map<String, UserRoadmapNodeEntity> states = stateRepository
                .findAllByUserRoadmapId(enrollment.getId()).stream().collect(
                        Collectors.toMap(UserRoadmapNodeEntity::getNodeId, Function.identity()));
        List<RoadmapNodeEntity> orderedNodes = nodeRepository
                .findAllByTemplateIdInRoadmapOrder(enrollment.getTemplateId());
        Map<String, Integer> roadmapOrder = new HashMap<>();
        for (int index = 0; index < orderedNodes.size(); index++) {
            roadmapOrder.put(orderedNodes.get(index).getId(), index);
        }
        List<RoadmapNodeEntity> candidates = orderedNodes.stream()
                .filter(node -> {
                    UserRoadmapNodeEntity state = states.get(node.getId());
                    return state != null
                            && state.getCompletionStatus() != CompletionStatus.COMPLETED;
                })
                .sorted(Comparator
                        .comparingInt((RoadmapNodeEntity node) ->
                                states.get(node.getId()).getQuizStatus() == QuizStatus.FAILED ? 0 : 1)
                        .thenComparingInt(node -> roadmapOrder.get(node.getId())))
                .toList();
        for (RoadmapNodeEntity node : candidates) {
            UserRoadmapNodeEntity nodeState = states.get(node.getId());
            if (nodeState == null || nodeState.getCompletionStatus() == CompletionStatus.COMPLETED) {
                continue;
            }
            RoadmapScheduleItemEntity old = byState.get(nodeState.getId());
            if (old != null && old.getStatus() != RoadmapScheduleItemStatus.PLANNED) {
                continue;
            }
            LocalDate date = firstAvailableDate(
                    start, end, node.getEstimatedMinutes(), usedMinutes, settings);
            if (date == null) {
                break;
            }
            RoadmapScheduleItemEntity item = old;
            if (item == null) {
                item = new RoadmapScheduleItemEntity(
                        UUID.randomUUID().toString(), schedule.getId(), ownerId,
                        enrollment.getId(), nodeState.getId(), node.getId(), date,
                        node.getEstimatedMinutes(), now);
                itemRepository.save(item);
                byState.put(nodeState.getId(), item);
            } else {
                item.reschedule(date, node.getEstimatedMinutes(), now);
            }
            usedMinutes.merge(date, node.getEstimatedMinutes(), Integer::sum);
        }
        schedule.markRefreshed(
                enrollment.getId(), settings.timeZone(), settings.capacity(),
                settings.weekendsEnabled(), now);
        return response(schedule, start, end, byState.values(), orderedNodes);
    }

    private LocalDate firstAvailableDate(
            LocalDate start, LocalDate end, int minutes, Map<LocalDate, Integer> used,
            Settings settings
    ) {
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            boolean weekend = date.getDayOfWeek() == DayOfWeek.SATURDAY
                    || date.getDayOfWeek() == DayOfWeek.SUNDAY;
            if (weekend && !settings.weekendsEnabled()) {
                continue;
            }
            if (used.getOrDefault(date, 0) + minutes <= settings.capacity()) {
                return date;
            }
        }
        return null;
    }

    private Settings settings(String ownerId) {
        return settingsRepository.findById(ownerId)
                .map(value -> new Settings(
                        value.getTimeZone(), value.getDailyStudyLimitMinutes(),
                        value.getWeekendPreference() != WeekendPreference.LESS))
                .orElse(new Settings(DEFAULT_ZONE, DEFAULT_CAPACITY, true));
    }

    private RoadmapScheduleResponse response(
            RoadmapScheduleStateEntity schedule,
            LocalDate start,
            LocalDate end,
            Iterable<RoadmapScheduleItemEntity> items,
            List<RoadmapNodeEntity> orderedNodes
    ) {
        Map<String, RoadmapNodeEntity> nodeById = orderedNodes.stream().collect(
                Collectors.toMap(RoadmapNodeEntity::getId, Function.identity()));
        Map<String, Integer> roadmapOrder = new HashMap<>();
        for (int index = 0; index < orderedNodes.size(); index++) {
            roadmapOrder.put(orderedNodes.get(index).getId(), index);
        }
        Map<LocalDate, List<RoadmapScheduleItemEntity>> byDate = new HashMap<>();
        for (RoadmapScheduleItemEntity item : items) {
            if (!item.getScheduledDate().isBefore(start) && !item.getScheduledDate().isAfter(end)) {
                byDate.computeIfAbsent(item.getScheduledDate(), ignored -> new ArrayList<>()).add(item);
            }
        }
        List<RoadmapScheduleResponse.Day> days = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            List<RoadmapScheduleResponse.Item> dayItems = byDate.getOrDefault(date, List.of())
                    .stream()
                    .sorted(Comparator.comparingInt(item -> roadmapOrder.get(item.getNodeId())))
                    .map(item -> {
                        RoadmapNodeEntity node = nodeById.get(item.getNodeId());
                        return new RoadmapScheduleResponse.Item(
                                item.getId(), node.getId(), node.getNodeCode(), node.getTitle(),
                                item.getPlannedMinutes(), item.getStatus());
                    }).toList();
            days.add(new RoadmapScheduleResponse.Day(
                    date, dayItems.stream().mapToInt(RoadmapScheduleResponse.Item::plannedMinutes).sum(),
                    dayItems));
        }
        return new RoadmapScheduleResponse(
                schedule.getId(), schedule.getTimeZone(), schedule.getDailyCapacityMinutes(),
                schedule.isWeekendsEnabled(), List.copyOf(days));
    }

    private record Settings(String timeZone, int capacity, boolean weekendsEnabled) { }
}
