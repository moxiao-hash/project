package com.moxiao.studypilot.roadmap.application;

import com.moxiao.studypilot.roadmap.api.RoadmapScheduleResponse;
import com.moxiao.studypilot.roadmap.domain.CompletionStatus;
import com.moxiao.studypilot.roadmap.domain.LearningStatus;
import com.moxiao.studypilot.roadmap.domain.QuizStatus;
import com.moxiao.studypilot.roadmap.domain.RoadmapScheduleItemStatus;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodePrerequisiteEntity;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodePrerequisiteJpaRepository;
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
import java.util.PriorityQueue;
import java.util.Set;
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
    private final RoadmapNodePrerequisiteJpaRepository prerequisiteRepository;
    private final RoadmapScheduleStateJpaRepository scheduleRepository;
    private final RoadmapScheduleItemJpaRepository itemRepository;
    private final UserSettingsJpaRepository settingsRepository;

    public RoadmapScheduleService(
            UserRoadmapJpaRepository enrollmentRepository,
            UserRoadmapNodeJpaRepository stateRepository,
            RoadmapNodeJpaRepository nodeRepository,
            RoadmapNodePrerequisiteJpaRepository prerequisiteRepository,
            RoadmapScheduleStateJpaRepository scheduleRepository,
            RoadmapScheduleItemJpaRepository itemRepository,
            UserSettingsJpaRepository settingsRepository
    ) {
        this.enrollmentRepository = enrollmentRepository;
        this.stateRepository = stateRepository;
        this.nodeRepository = nodeRepository;
        this.prerequisiteRepository = prerequisiteRepository;
        this.scheduleRepository = scheduleRepository;
        this.itemRepository = itemRepository;
        this.settingsRepository = settingsRepository;
    }

    @Transactional
    public RoadmapScheduleResponse getOrCreate(String ownerId, LocalDate from, LocalDate to) {
        UserRoadmapEntity enrollment = currentEnrollment(ownerId);
        Settings settings = settings(ownerId);
        LocalDate today = LocalDate.now(ZoneId.of(settings.timeZone()));
        LocalDate start = from == null ? today : from;
        LocalDate end = to == null ? start.plusDays(6) : to;
        validateWindow(start, end);
        RoadmapScheduleStateEntity schedule = scheduleRepository
                .findByOwnerIdAndUserRoadmapId(ownerId, enrollment.getId()).orElse(null);
        if (schedule == null) {
            refresh(ownerId, today, today.plusDays(6));
            schedule = scheduleRepository
                    .findByOwnerIdAndUserRoadmapId(ownerId, enrollment.getId())
                    .orElseThrow(() -> new IllegalStateException("路线日程创建失败"));
        }
        return read(schedule, enrollment, start, end);
    }

    @Transactional
    public RoadmapScheduleResponse refresh(String ownerId, LocalDate from, LocalDate to) {
        UserRoadmapEntity enrollment = enrollmentRepository
                .findByOwnerIdAndActiveSlotForUpdate(ownerId, CURRENT)
                .orElseThrow(() -> new ResourceNotFoundException("当前学习路线不存在"));
        Settings settings = settings(ownerId);
        LocalDate today = LocalDate.now(ZoneId.of(settings.timeZone()));
        LocalDate start = from == null ? today : from;
        LocalDate end = to == null ? today.plusDays(6) : to;
        validateWindow(start, end);
        if (!start.equals(today)) {
            throw new IllegalArgumentException("滚动刷新只能从用户时区的今天开始");
        }
        Instant now = Instant.now();
        RoadmapScheduleStateEntity schedule = scheduleRepository
                .findByOwnerIdAndUserRoadmapId(ownerId, enrollment.getId())
                .orElseGet(() -> scheduleRepository.save(new RoadmapScheduleStateEntity(
                        UUID.randomUUID().toString(), ownerId, enrollment.getId(),
                        settings.timeZone(), settings.capacity(), settings.weekendsEnabled(), now)));
        List<UserRoadmapNodeEntity> stateList = stateRepository
                .findAllByUserRoadmapId(enrollment.getId());
        Map<String, UserRoadmapNodeEntity> stateByNode = stateList.stream().collect(
                Collectors.toMap(UserRoadmapNodeEntity::getNodeId, Function.identity()));
        Map<String, UserRoadmapNodeEntity> stateById = stateList.stream().collect(
                Collectors.toMap(UserRoadmapNodeEntity::getId, Function.identity()));
        List<RoadmapNodeEntity> catalogOrder = nodeRepository
                .findAllByTemplateIdInRoadmapOrder(enrollment.getTemplateId());
        Set<String> failedNodes = stateList.stream()
                .filter(state -> state.getQuizStatus() == QuizStatus.FAILED)
                .map(UserRoadmapNodeEntity::getNodeId)
                .collect(Collectors.toSet());
        List<RoadmapNodeEntity> orderedNodes = stableTopologicalOrder(
                catalogOrder,
                prerequisiteRepository.findAllByTemplateId(enrollment.getTemplateId()),
                failedNodes);
        List<RoadmapScheduleItemEntity> existing = itemRepository
                .findAllByOwnerIdAndUserRoadmapId(ownerId, enrollment.getId());
        Map<String, RoadmapScheduleItemEntity> byState = existing.stream().collect(
                Collectors.toMap(RoadmapScheduleItemEntity::getUserRoadmapNodeId, Function.identity()));
        Map<LocalDate, Integer> usedMinutes = new HashMap<>();
        for (RoadmapScheduleItemEntity item : existing) {
            UserRoadmapNodeEntity nodeState = stateById.get(item.getUserRoadmapNodeId());
            if (nodeState != null && nodeState.getCompletionStatus() == CompletionStatus.COMPLETED) {
                item.complete(now);
            } else if (nodeState != null
                    && nodeState.getLearningStatus() == LearningStatus.IN_PROGRESS) {
                item.start(now);
            }
            if (item.getStatus() != RoadmapScheduleItemStatus.PLANNED
                    && inWindow(item.getScheduledDate(), start, end)) {
                usedMinutes.merge(item.getScheduledDate(), item.getPlannedMinutes(), Integer::sum);
            }
        }
        for (RoadmapNodeEntity node : orderedNodes) {
            UserRoadmapNodeEntity nodeState = stateByNode.get(node.getId());
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
                if (nodeState.getLearningStatus() == LearningStatus.IN_PROGRESS) {
                    item.start(now);
                }
                item = itemRepository.save(item);
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

    static List<RoadmapNodeEntity> stableTopologicalOrder(
            List<RoadmapNodeEntity> nodes,
            List<RoadmapNodePrerequisiteEntity> prerequisites,
            Set<String> failedNodeIds
    ) {
        Map<String, RoadmapNodeEntity> byId = nodes.stream().collect(
                Collectors.toMap(RoadmapNodeEntity::getId, Function.identity()));
        Map<String, Integer> displayOrder = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> dependants = new HashMap<>();
        for (int index = 0; index < nodes.size(); index++) {
            displayOrder.put(nodes.get(index).getId(), index);
            indegree.put(nodes.get(index).getId(), 0);
        }
        for (RoadmapNodePrerequisiteEntity edge : prerequisites) {
            if (!byId.containsKey(edge.getNodeId())
                    || !byId.containsKey(edge.getPrerequisiteNodeId())) {
                continue;
            }
            indegree.merge(edge.getNodeId(), 1, Integer::sum);
            dependants.computeIfAbsent(edge.getPrerequisiteNodeId(), ignored -> new ArrayList<>())
                    .add(edge.getNodeId());
        }
        Comparator<String> priority = Comparator
                .comparingInt((String id) -> failedNodeIds.contains(id) ? 0 : 1)
                .thenComparingInt(displayOrder::get);
        PriorityQueue<String> ready = new PriorityQueue<>(priority);
        indegree.forEach((id, degree) -> {
            if (degree == 0) {
                ready.add(id);
            }
        });
        List<RoadmapNodeEntity> result = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.remove();
            result.add(byId.get(id));
            for (String dependant : dependants.getOrDefault(id, List.of())) {
                int degree = indegree.merge(dependant, -1, Integer::sum);
                if (degree == 0) {
                    ready.add(dependant);
                }
            }
        }
        if (result.size() != nodes.size()) {
            throw new IllegalStateException("路线前置图存在环，无法生成日程");
        }
        return List.copyOf(result);
    }

    private RoadmapScheduleResponse read(
            RoadmapScheduleStateEntity schedule,
            UserRoadmapEntity enrollment,
            LocalDate start,
            LocalDate end
    ) {
        List<RoadmapNodeEntity> catalogOrder = nodeRepository
                .findAllByTemplateIdInRoadmapOrder(enrollment.getTemplateId());
        return response(
                schedule, start, end,
                itemRepository.findAllByOwnerIdAndUserRoadmapId(
                        enrollment.getOwnerId(), enrollment.getId()),
                stableTopologicalOrder(
                        catalogOrder,
                        prerequisiteRepository.findAllByTemplateId(enrollment.getTemplateId()),
                        Set.of()));
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
            if (inWindow(item.getScheduledDate(), start, end)) {
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

    private UserRoadmapEntity currentEnrollment(String ownerId) {
        return enrollmentRepository.findByOwnerIdAndActiveSlot(ownerId, CURRENT)
                .orElseThrow(() -> new ResourceNotFoundException("当前学习路线不存在"));
    }

    private Settings settings(String ownerId) {
        return settingsRepository.findById(ownerId)
                .map(value -> new Settings(
                        value.getTimeZone(), value.getDailyStudyLimitMinutes(),
                        value.getWeekendPreference() != WeekendPreference.LESS))
                .orElse(new Settings(DEFAULT_ZONE, DEFAULT_CAPACITY, true));
    }

    private static void validateWindow(LocalDate start, LocalDate end) {
        if (!end.equals(start.plusDays(6))) {
            throw new IllegalArgumentException("路线日程必须是包含首尾日期的连续七天");
        }
    }

    private static boolean inWindow(LocalDate date, LocalDate start, LocalDate end) {
        return !date.isBefore(start) && !date.isAfter(end);
    }

    private record Settings(String timeZone, int capacity, boolean weekendsEnabled) { }
}
