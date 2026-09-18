package com.moxiao.studypilot.agent.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * 官方价格目录。
 *
 * <p>价格按"每百万 token"配置并带版本日期；调价时新增一个 {@link PriceSpec}，
 * 历史记录继续保留原估算，因此金额永远可追溯到当时的价格版本。</p>
 *
 * <p>未知模型返回 {@link Optional#empty()}，调用方必须落库为"不可估算"（NULL），
 * 绝不允许写 0 冒充已计量。</p>
 */
public final class ModelPricingCatalog {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000L);

    public record PriceSpec(
            String version,
            LocalDate effectiveFrom,
            BigDecimal inputPerMillion,
            BigDecimal cachedInputPerMillion,
            BigDecimal outputPerMillion,
            String currency
    ) {
    }

    public record EstimatedCost(BigDecimal amount, String currency, String priceVersion) {
    }

    private final Map<String, PriceSpec> prices;

    private ModelPricingCatalog(Map<String, PriceSpec> prices) {
        this.prices = Map.copyOf(prices);
    }

    public static ModelPricingCatalog of(Map<String, PriceSpec> prices) {
        return new ModelPricingCatalog(prices);
    }

    /**
     * 官方价格目录。
     *
     * <p>数值必须来自 DeepSeek 官方定价页的当前版本，并同步更新 {@code version} 与
     * {@code effectiveFrom}；在填入官方价格之前保持空目录，所有调用按"不可估算"处理，
     * 这是刻意的保守取值，不用零成本冒充已计量。</p>
     */
    public static ModelPricingCatalog official() {
        return of(Map.of());
    }

    public Optional<PriceSpec> find(String modelName) {
        return Optional.ofNullable(modelName).map(prices::get);
    }

    public Optional<EstimatedCost> estimate(String modelName, ModelUsage usage) {
        PriceSpec spec = find(modelName).orElse(null);
        if (spec == null || usage == null) {
            return Optional.empty();
        }
        BigDecimal amount = spec.inputPerMillion()
                .multiply(BigDecimal.valueOf(usage.uncachedPromptTokens()))
                .add(spec.cachedInputPerMillion().multiply(BigDecimal.valueOf(usage.cachedPromptTokens())))
                // reasoning token 已包含在 completion token 内，不得重复计费。
                .add(spec.outputPerMillion().multiply(BigDecimal.valueOf(usage.completionTokens())))
                .divide(MILLION, 8, RoundingMode.HALF_UP);
        return Optional.of(new EstimatedCost(amount, spec.currency(), spec.version()));
    }
}
