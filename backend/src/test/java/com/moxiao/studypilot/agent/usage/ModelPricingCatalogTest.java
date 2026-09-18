package com.moxiao.studypilot.agent.usage;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModelPricingCatalogTest {

    private static final String MODEL = "test-model";

    private final ModelPricingCatalog catalog = ModelPricingCatalog.of(Map.of(MODEL,
            new ModelPricingCatalog.PriceSpec("test-2026-09-18", LocalDate.of(2026, 9, 18),
                    new BigDecimal("2.00"), new BigDecimal("0.50"), new BigDecimal("8.00"), "CNY")));

    @Test
    void pricesCachedInputCheaperThanUncached() {
        var usage = new ModelUsage(MODEL, 1_000_000, 400_000, 0, null, 10L);
        var estimate = catalog.estimate(MODEL, usage).orElseThrow();
        // 600k 非缓存 * 2.00 + 400k 缓存 * 0.50 = 1.40
        assertThat(estimate.amount()).isEqualByComparingTo("1.40000000");
        assertThat(estimate.priceVersion()).isEqualTo("test-2026-09-18");
        assertThat(estimate.currency()).isEqualTo("CNY");
    }

    @Test
    void unknownModelIsNotEstimatedInsteadOfZero() {
        var usage = new ModelUsage("unknown-model", 100, 0, 10, null, 1L);
        assertThat(catalog.estimate("unknown-model", usage)).isEmpty();
    }

    @Test
    void missingReasoningTokensStillEstimates() {
        var usage = new ModelUsage(MODEL, 10, 0, 10, null, 1L);
        assertThat(catalog.estimate(MODEL, usage)).isPresent();
    }

    @Test
    void reasoningTokensAreNotDoubleCharged() {
        var without = new ModelUsage(MODEL, 0, 0, 1000, null, 1L);
        var with = new ModelUsage(MODEL, 0, 0, 1000, 800, 1L);
        assertThat(catalog.estimate(MODEL, with).orElseThrow().amount())
                .isEqualByComparingTo(catalog.estimate(MODEL, without).orElseThrow().amount());
    }
}
