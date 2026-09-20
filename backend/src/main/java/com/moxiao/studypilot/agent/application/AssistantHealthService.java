package com.moxiao.studypilot.agent.application;

import com.moxiao.studypilot.agent.api.AssistantHealthResponse;
import com.moxiao.studypilot.agent.api.AssistantModelUsageStats;
import com.moxiao.studypilot.agent.domain.ExecutionStatus;
import com.moxiao.studypilot.agent.infrastructure.AgentExecutionEntity;
import com.moxiao.studypilot.agent.infrastructure.AgentExecutionJpaRepository;
import com.moxiao.studypilot.agent.usage.AssistantModelUsageEntity;
import com.moxiao.studypilot.agent.usage.AssistantModelUsageJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 汇总已认证用户自己的助手健康指标。
 *
 * <p>执行层指标来自 Agent 执行记录，模型层指标来自 {@code assistant_model_usage}；
 * 两类查询都只按传入的 owner 过滤，避免跨用户泄露。金额统一 {@link BigDecimal}。</p>
 */
@Service
public class AssistantHealthService {

    private static final String STATUS_FAILED = "FAILED";
    private static final String PRICE_KNOWN = "KNOWN";
    private static final String PRICE_UNKNOWN = "UNKNOWN";
    private static final String PRICE_NONE = "NONE";

    private final AgentExecutionJpaRepository executionRepository;
    private final AssistantModelUsageJpaRepository usageRepository;

    public AssistantHealthService(
            AgentExecutionJpaRepository executionRepository,
            AssistantModelUsageJpaRepository usageRepository
    ) {
        this.executionRepository = executionRepository;
        this.usageRepository = usageRepository;
    }

    @Transactional(readOnly = true)
    public AssistantHealthResponse summarize(String ownerId) {
        List<AgentExecutionEntity> executions =
                executionRepository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId);
        List<AssistantModelUsageEntity> usage =
                usageRepository.findAllByOwnerIdOrderByOccurredAtDesc(ownerId);

        int succeeded = count(executions, ExecutionStatus.SUCCEEDED);
        int failed = count(executions, ExecutionStatus.FAILED);
        int decided = succeeded + failed;
        long latencySamples = executions.stream()
                .filter(item -> item.getLatencyMs() != null).count();
        long totalLatency = executions.stream()
                .map(AgentExecutionEntity::getLatencyMs)
                .filter(Objects::nonNull).mapToLong(Long::longValue).sum();
        BigDecimal executionCost = executions.stream()
                .map(AgentExecutionEntity::getEstimatedCost)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Aggregate aggregate = aggregate(usage);
        List<AssistantModelUsageStats> models = byModel(usage);

        return new AssistantHealthResponse(
                executions.size(), succeeded, failed,
                decided == 0 ? 0.0 : (double) succeeded / decided,
                sumTokens(executions, true), sumTokens(executions, false), executionCost,
                latencySamples == 0 ? 0 : totalLatency / latencySamples,
                count(executions, ExecutionStatus.WAITING_CONFIRMATION),
                executions.stream().filter(item -> item.getEstimatedCost() != null).count(),
                executions.stream().filter(item -> item.getPromptTokens() != null
                        && item.getCompletionTokens() != null).count(),
                latencySamples,
                aggregate.calls,
                aggregate.failedCalls,
                rate(aggregate.failedCalls, aggregate.calls),
                aggregate.promptTokens,
                aggregate.cachedPromptTokens,
                aggregate.uncachedPromptTokens,
                aggregate.completionTokens,
                aggregate.reasoningTokens,
                aggregate.totalTokens,
                aggregate.unknownPriceCalls,
                aggregate.estimatedCost,
                aggregate.currency,
                aggregate.priceStatus,
                aggregate.priceVersion,
                aggregate.p50LatencyMs,
                aggregate.p95LatencyMs,
                models);
    }

    private static int count(List<AgentExecutionEntity> values, ExecutionStatus status) {
        return (int) values.stream().filter(value -> value.getStatus() == status).count();
    }

    private static long sumTokens(List<AgentExecutionEntity> values, boolean prompt) {
        return values.stream().mapToLong(value -> {
            Integer tokens = prompt ? value.getPromptTokens() : value.getCompletionTokens();
            return tokens == null ? 0 : tokens;
        }).sum();
    }

    private static List<AssistantModelUsageStats> byModel(List<AssistantModelUsageEntity> usage) {
        Map<String, List<AssistantModelUsageEntity>> grouped = new LinkedHashMap<>();
        usage.stream()
                .sorted(Comparator.comparing(AssistantModelUsageEntity::getModelName))
                .forEach(item -> grouped
                        .computeIfAbsent(item.getModelName(), key -> new ArrayList<>())
                        .add(item));
        List<AssistantModelUsageStats> result = new ArrayList<>();
        grouped.forEach((modelName, entries) -> {
            Aggregate aggregate = aggregate(entries);
            String provider = entries.stream()
                    .map(AssistantModelUsageEntity::getProvider)
                    .filter(Objects::nonNull)
                    .findFirst().orElse(null);
            result.add(new AssistantModelUsageStats(
                    modelName,
                    provider,
                    aggregate.calls,
                    aggregate.failedCalls,
                    rate(aggregate.failedCalls, aggregate.calls),
                    aggregate.promptTokens,
                    aggregate.cachedPromptTokens,
                    aggregate.uncachedPromptTokens,
                    aggregate.completionTokens,
                    aggregate.reasoningTokens,
                    aggregate.totalTokens,
                    aggregate.estimatedCost,
                    aggregate.currency,
                    aggregate.priceStatus,
                    aggregate.priceVersion,
                    aggregate.p50LatencyMs,
                    aggregate.p95LatencyMs));
        });
        return result;
    }

    private static Aggregate aggregate(List<AssistantModelUsageEntity> entries) {
        Aggregate aggregate = new Aggregate();
        aggregate.calls = entries.size();
        aggregate.failedCalls = entries.stream()
                .filter(item -> STATUS_FAILED.equals(item.getStatus())).count();
        aggregate.promptTokens = entries.stream()
                .mapToLong(item -> value(item.getPromptTokens())).sum();
        aggregate.cachedPromptTokens = entries.stream()
                .mapToLong(item -> value(item.getCachedPromptTokens())).sum();
        aggregate.uncachedPromptTokens = entries.stream()
                .mapToLong(item -> Math.max(0L,
                        value(item.getPromptTokens()) - value(item.getCachedPromptTokens())))
                .sum();
        aggregate.completionTokens = entries.stream()
                .mapToLong(item -> value(item.getCompletionTokens())).sum();
        aggregate.reasoningTokens = entries.stream()
                .mapToLong(item -> value(item.getReasoningTokens())).sum();
        aggregate.totalTokens = entries.stream()
                .mapToLong(item -> item.getTotalTokens() != null
                        ? item.getTotalTokens()
                        : value(item.getPromptTokens()) + value(item.getCompletionTokens()))
                .sum();
        aggregate.unknownPriceCalls = entries.stream()
                .filter(item -> item.getEstimatedCost() == null).count();
        BigDecimal knownCost = entries.stream()
                .map(AssistantModelUsageEntity::getEstimatedCost)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (aggregate.calls == 0) {
            aggregate.estimatedCost = BigDecimal.ZERO;
            aggregate.priceStatus = PRICE_NONE;
        } else if (aggregate.unknownPriceCalls > 0) {
            aggregate.estimatedCost = null;
            aggregate.priceStatus = PRICE_UNKNOWN;
        } else {
            aggregate.estimatedCost = knownCost;
            aggregate.priceStatus = PRICE_KNOWN;
        }
        aggregate.currency = entries.stream()
                .map(AssistantModelUsageEntity::getCurrency)
                .filter(Objects::nonNull).findFirst().orElse(null);
        aggregate.priceVersion = singleVersion(entries);
        List<Long> latencies = entries.stream()
                .map(AssistantModelUsageEntity::getLatencyMs)
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        aggregate.p50LatencyMs = percentile(latencies, 0.50);
        aggregate.p95LatencyMs = percentile(latencies, 0.95);
        return aggregate;
    }

    private static String singleVersion(List<AssistantModelUsageEntity> entries) {
        List<String> versions = entries.stream()
                .map(AssistantModelUsageEntity::getPriceVersion)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return versions.size() == 1 ? versions.get(0) : null;
    }

    /** 最近秩百分位：p50 取中位数，p95 取第 ceil(0.95n) 个，全部使用已排序延迟。 */
    static long percentile(List<Long> sorted, double fraction) {
        if (sorted.isEmpty()) {
            return 0L;
        }
        int rank = (int) Math.ceil(fraction * sorted.size());
        int index = Math.min(sorted.size() - 1, Math.max(0, rank - 1));
        return sorted.get(index);
    }

    private static long value(Integer candidate) {
        return candidate == null ? 0L : candidate;
    }

    private static double rate(long failed, long calls) {
        return calls == 0 ? 0.0 : (double) failed / calls;
    }

    private static final class Aggregate {
        private long calls;
        private long failedCalls;
        private long promptTokens;
        private long cachedPromptTokens;
        private long uncachedPromptTokens;
        private long completionTokens;
        private long reasoningTokens;
        private long totalTokens;
        private long unknownPriceCalls;
        private BigDecimal estimatedCost;
        private String currency;
        private String priceStatus;
        private String priceVersion;
        private long p50LatencyMs;
        private long p95LatencyMs;
    }
}
