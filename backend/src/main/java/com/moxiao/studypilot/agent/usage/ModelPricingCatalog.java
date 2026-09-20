package com.moxiao.studypilot.agent.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 官方价格目录。
 *
 * <p>价格按"每百万 token / 美元"记录，并按 UTC 高峰/非高峰两个时段分别保存
 * 缓存命中输入、缓存未命中输入与输出单价。高峰时段为周一至周五
 * 01:00–04:00 与 06:00–10:00 UTC（起始含、结束不含）；其余时间一律非高峰。</p>
 *
 * <p>未知模型返回 {@link Optional#empty()}，调用方必须落库为"不可估算"（NULL），
 * 绝不允许写 0 冒充已计量。历史记录保存自己的金额与价格版本，调价只影响新记录。</p>
 */
public final class ModelPricingCatalog {

    /** 计费时段：高峰 / 非高峰。 */
    public enum RateWindow {
        PEAK,
        OFF_PEAK
    }

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000L);

    private static final LocalTime PEAK_MORNING_START = LocalTime.of(1, 0);
    private static final LocalTime PEAK_MORNING_END = LocalTime.of(4, 0);
    private static final LocalTime PEAK_EVENING_START = LocalTime.of(6, 0);
    private static final LocalTime PEAK_EVENING_END = LocalTime.of(10, 0);

    /** 单一模型在某个价格版本下的完整单价；金额单位为每百万 token。 */
    public record PriceSpec(
            String modelName,
            String modelVersion,
            String version,
            LocalDate effectiveFrom,
            String currency,
            String sourceUrl,
            BigDecimal cacheHitPeakPerMillion,
            BigDecimal cacheHitOffPeakPerMillion,
            BigDecimal cacheMissPeakPerMillion,
            BigDecimal cacheMissOffPeakPerMillion,
            BigDecimal outputPeakPerMillion,
            BigDecimal outputOffPeakPerMillion
    ) {

        public boolean isComplete() {
            return cacheHitPeakPerMillion != null
                    && cacheHitOffPeakPerMillion != null
                    && cacheMissPeakPerMillion != null
                    && cacheMissOffPeakPerMillion != null
                    && outputPeakPerMillion != null
                    && outputOffPeakPerMillion != null;
        }
    }

    /** 一次调用的估算金额；{@code window} 说明采用了高峰还是非高峰单价。 */
    public record EstimatedCost(
            BigDecimal amount,
            String currency,
            String priceVersion,
            RateWindow window
    ) {
    }

    private final Map<String, PriceSpec> prices;

    private ModelPricingCatalog(Map<String, PriceSpec> prices) {
        this.prices = Map.copyOf(prices);
    }

    public static ModelPricingCatalog of(Map<String, PriceSpec> prices) {
        return new ModelPricingCatalog(prices);
    }

    public static ModelPricingCatalog empty() {
        return of(Map.of());
    }

    /**
     * 官方价格目录（来源 {@code https://api-docs.deepseek.com/quick_start/pricing/}，
     * 核对日期 2026-09-20）。
     *
     * <p>{@code deepseek-flash} 与旧名 {@code deepseek-v4-flash}、
     * {@code deepseek-v4-flash-vision-exp} 都由 DeepSeek-V4.1-Flash 提供服务，
     * 统一按 Flash 价格计费；{@code deepseek-v4-pro} 对应 DeepSeek-V4-Pro-0813。
     * 其余模型保持"不可估算"。</p>
     */
    public static ModelPricingCatalog official() {
        String source = "https://api-docs.deepseek.com/quick_start/pricing/";
        String version = "deepseek-pricing-2026-09-20";
        LocalDate effectiveFrom = LocalDate.of(2026, 9, 20);
        PriceSpec flash = new PriceSpec(
                "deepseek-flash", "DeepSeek-V4.1-Flash", version, effectiveFrom, "USD", source,
                new BigDecimal("0.006"), new BigDecimal("0.003"),
                new BigDecimal("0.30"), new BigDecimal("0.15"),
                new BigDecimal("1.20"), new BigDecimal("0.60"));
        PriceSpec pro = new PriceSpec(
                "deepseek-v4-pro", "DeepSeek-V4-Pro-0813", version, effectiveFrom, "USD", source,
                new BigDecimal("0.044"), new BigDecimal("0.022"),
                new BigDecimal("1.32"), new BigDecimal("0.66"),
                new BigDecimal("3.96"), new BigDecimal("1.98"));
        Map<String, PriceSpec> specs = new LinkedHashMap<>();
        specs.put("deepseek-flash", flash);
        specs.put("deepseek-v4-flash", flash);
        specs.put("deepseek-v4-flash-vision-exp", flash);
        specs.put("deepseek-v4-pro", pro);
        return of(specs);
    }

    public boolean isEmpty() {
        return prices.isEmpty();
    }

    public Optional<PriceSpec> find(String modelName) {
        return Optional.ofNullable(modelName).map(prices::get);
    }

    /**
     * 判断某个瞬间属于高峰还是非高峰计费时段。
     *
     * <p>高峰为周一至周五（UTC）01:00–04:00 与 06:00–10:00。官方页面还注明中国法定
     * 节假日全天按非高峰计价；本实现未内置节假日日历，节假日需要由调用方按部署配置
     * 调整，详见验证文档的已知限制。</p>
     */
    public static RateWindow rateWindowAt(Instant at) {
        ZonedDateTime utc = at.atZone(ZoneOffset.UTC);
        DayOfWeek day = utc.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return RateWindow.OFF_PEAK;
        }
        LocalTime time = utc.toLocalTime();
        boolean morningPeak = !time.isBefore(PEAK_MORNING_START)
                && time.isBefore(PEAK_MORNING_END);
        boolean eveningPeak = !time.isBefore(PEAK_EVENING_START)
                && time.isBefore(PEAK_EVENING_END);
        return morningPeak || eveningPeak ? RateWindow.PEAK : RateWindow.OFF_PEAK;
    }

    public Optional<EstimatedCost> estimate(String modelName, ModelUsage usage) {
        return estimate(modelName, usage, Instant.now());
    }

    /**
     * 按调用发生时刻选择高峰/非高峰单价并估算金额。
     *
     * <p>reasoning token 已包含在 completion token 内，只作为明细保存，此处不重复计费。</p>
     */
    public Optional<EstimatedCost> estimate(String modelName, ModelUsage usage, Instant occurredAt) {
        PriceSpec spec = find(modelName).orElse(null);
        if (spec == null || usage == null || !spec.isComplete()) {
            return Optional.empty();
        }
        RateWindow window = rateWindowAt(occurredAt);
        boolean peak = window == RateWindow.PEAK;
        BigDecimal cacheHit = peak
                ? spec.cacheHitPeakPerMillion() : spec.cacheHitOffPeakPerMillion();
        BigDecimal cacheMiss = peak
                ? spec.cacheMissPeakPerMillion() : spec.cacheMissOffPeakPerMillion();
        BigDecimal output = peak ? spec.outputPeakPerMillion() : spec.outputOffPeakPerMillion();
        BigDecimal amount = cacheMiss.multiply(BigDecimal.valueOf(usage.uncachedPromptTokens()))
                .add(cacheHit.multiply(BigDecimal.valueOf(usage.cachedPromptTokens())))
                .add(output.multiply(BigDecimal.valueOf(usage.completionTokens())))
                .divide(MILLION, 8, RoundingMode.HALF_UP);
        return Optional.of(new EstimatedCost(amount, spec.currency(), spec.version(), window));
    }
}
