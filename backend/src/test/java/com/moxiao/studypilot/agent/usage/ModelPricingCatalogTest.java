package com.moxiao.studypilot.agent.usage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModelPricingCatalogTest {

    private static final String MODEL = "test-model";

    private final ModelPricingCatalog catalog = ModelPricingCatalog.of(Map.of(MODEL,
            new ModelPricingCatalog.PriceSpec("test-model", "Test-Model-0813",
                    "test-2026-09-18",
                    Instant.parse("2026-09-18T00:00:00Z"),
                    Instant.parse("2026-09-19T00:00:00Z"),
                    "USD",
                    "https://example.invalid/pricing",
                    new BigDecimal("1.00"), new BigDecimal("0.50"),
                    new BigDecimal("4.00"), new BigDecimal("2.00"),
                    new BigDecimal("16.00"), new BigDecimal("8.00"))));

    @Test
    void officialCatalogPricesFlashAndProOffPeak() {
        ModelPricingCatalog official = ModelPricingCatalog.official();
        Instant offPeak = Instant.parse("2026-09-21T12:00:00Z");
        var usage = new ModelUsage("deepseek-flash", 1_000_000, 400_000, 200_000, null, 10L);
        assertThat(official.estimate("deepseek-flash", usage, offPeak).orElseThrow().amount())
                .isEqualByComparingTo("0.21120000");
        var proUsage = new ModelUsage("deepseek-v4-pro", 1_000_000, 400_000, 200_000, null, 10L);
        assertThat(official.estimate("deepseek-v4-pro", proUsage, offPeak).orElseThrow().amount())
                .isEqualByComparingTo("0.80080000");
    }

    @Test
    void officialCatalogChargesDoubleDuringPeakHours() {
        ModelPricingCatalog official = ModelPricingCatalog.official();
        Instant peak = Instant.parse("2026-09-21T02:00:00Z");
        var usage = new ModelUsage("deepseek-flash", 1_000_000, 400_000, 200_000, null, 10L);
        assertThat(official.estimate("deepseek-flash", usage, peak).orElseThrow().amount())
                .isEqualByComparingTo("0.42240000");
        var proUsage = new ModelUsage("deepseek-v4-pro", 1_000_000, 400_000, 200_000, null, 10L);
        assertThat(official.estimate("deepseek-v4-pro", proUsage, peak).orElseThrow().amount())
                .isEqualByComparingTo("1.60160000");
    }

    @Test
    void officialCatalogKeepsDocumentedAliasesAtFlashPrice() {
        ModelPricingCatalog official = ModelPricingCatalog.official();
        Instant offPeak = Instant.parse("2026-09-21T12:00:00Z");
        var usage = new ModelUsage("deepseek-v4-flash", 1_000_000, 400_000, 200_000, null, 10L);
        assertThat(official.estimate("deepseek-v4-flash", usage, offPeak).orElseThrow().amount())
                .isEqualByComparingTo("0.21120000");
        assertThat(official.find("deepseek-v4-flash-vision-exp").orElseThrow().modelVersion())
                .isEqualTo("DeepSeek-V4.1-Flash");
        assertThat(official.find("deepseek-flash").orElseThrow().sourceUrl())
                .isEqualTo("https://api-docs.deepseek.com/quick_start/pricing/");
    }

    @ParameterizedTest
    @CsvSource({
            "2026-09-21T00:59:59Z, OFF_PEAK",
            "2026-09-21T01:00:00Z, PEAK",
            "2026-09-21T03:59:59Z, PEAK",
            "2026-09-21T04:00:00Z, OFF_PEAK",
            "2026-09-21T05:59:59Z, OFF_PEAK",
            "2026-09-21T06:00:00Z, PEAK",
            "2026-09-21T09:59:59Z, PEAK",
            "2026-09-21T10:00:00Z, OFF_PEAK",
            "2026-09-21T23:59:59Z, OFF_PEAK",
            "2026-09-19T02:00:00Z, OFF_PEAK",
            "2026-09-20T02:00:00Z, OFF_PEAK",
    })
    void rateWindowBoundariesAreDeterministic(String instant, String expected) {
        assertThat(ModelPricingCatalog.rateWindowAt(Instant.parse(instant)))
                .isEqualTo(ModelPricingCatalog.RateWindow.valueOf(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"deepseek-chat", "gpt-5", "unknown-model"})
    void modelsOutsideOfficialCatalogStayUnknown(String modelName) {
        var usage = new ModelUsage(modelName, 100, 0, 10, null, 1L);
        assertThat(ModelPricingCatalog.official().estimate(modelName, usage,
                Instant.parse("2026-09-21T12:00:00Z"))).isEmpty();
    }

    @Test
    void unknownModelIsNotEstimatedInsteadOfZero() {
        var usage = new ModelUsage("unknown-model", 100, 0, 10, null, 1L);
        assertThat(catalog.estimate("unknown-model", usage)).isEmpty();
    }

    @Test
    void reasoningTokensAreNotDoubleCharged() {
        Instant offPeak = Instant.parse("2026-09-21T12:00:00Z");
        var without = new ModelUsage(MODEL, 0, 0, 1_000, null, 1L);
        var with = new ModelUsage(MODEL, 0, 0, 1_000, 800, 1L);
        assertThat(catalog.estimate(MODEL, with, offPeak).orElseThrow().amount())
                .isEqualByComparingTo(
                        catalog.estimate(MODEL, without, offPeak).orElseThrow().amount());
    }

    @Test
    void peakWindowIsRecordedOnTheEstimate() {
        Instant peak = Instant.parse("2026-09-21T02:00:00Z");
        var usage = new ModelUsage(MODEL, 1_000, 0, 1_000, null, 1L);
        var estimate = catalog.estimate(MODEL, usage, peak).orElseThrow();
        assertThat(estimate.window()).isEqualTo(ModelPricingCatalog.RateWindow.PEAK);
        assertThat(estimate.currency()).isEqualTo("USD");
        assertThat(estimate.priceVersion()).isEqualTo("test-2026-09-18");
    }

    @Test
    void officialCatalogPublishesEffectiveVerifiedAndSourceMetadata() {
        var flash = ModelPricingCatalog.official().find("deepseek-flash").orElseThrow();
        assertThat(flash.version()).isEqualTo("deepseek-pricing-2026-09-20");
        assertThat(flash.effectiveAt()).isEqualTo(Instant.parse("2026-08-16T16:00:00Z"));
        assertThat(flash.verifiedAt()).isEqualTo(Instant.parse("2026-09-20T00:00:00Z"));
        assertThat(flash.sourceUrl())
                .isEqualTo("https://api-docs.deepseek.com/quick_start/pricing/");
    }

    @Test
    void officialCatalogIsUnknownBeforeThePriceEffectiveInstant() {
        var usage = new ModelUsage("deepseek-flash", 1_000_000, 0, 1_000_000, null, 1L);
        Instant effectiveAt = Instant.parse("2026-08-16T16:00:00Z");
        assertThat(ModelPricingCatalog.official()
                .estimate("deepseek-flash", usage, effectiveAt.minusNanos(1))).isEmpty();
    }

    @Test
    void officialCatalogPricesExactlyAtThePriceEffectiveInstant() {
        var usage = new ModelUsage("deepseek-flash", 1_000_000, 0, 1_000_000, null, 1L);
        Instant effectiveAt = Instant.parse("2026-08-16T16:00:00Z");
        var estimate = ModelPricingCatalog.official()
                .estimate("deepseek-flash", usage, effectiveAt);
        assertThat(estimate).isPresent();
        // 2026-08-16T16:00:00Z 是周日，按非高峰计价：0.15 + 0.60 = 0.75 USD。
        assertThat(estimate.orElseThrow().amount()).isEqualByComparingTo("0.75000000");
        assertThat(estimate.orElseThrow().window())
                .isEqualTo(ModelPricingCatalog.RateWindow.OFF_PEAK);
    }

    @Test
    void customCatalogEntryIsUnknownBeforeItsOwnEffectiveInstant() {
        var usage = new ModelUsage(MODEL, 100, 0, 1_000, null, 1L);
        Instant effectiveAt = Instant.parse("2026-09-18T00:00:00Z");
        assertThat(catalog.estimate(MODEL, usage, effectiveAt.minusNanos(1))).isEmpty();
        assertThat(catalog.estimate(MODEL, usage, effectiveAt)).isPresent();
    }

    @Test
    void outputHoldCostUsesTheHigherPeakOutputPrice() {
        Instant offPeak = Instant.parse("2026-09-21T12:00:00Z");
        // 高峰输出价 16.00 高于非高峰 8.00；预占按 16.00 计算上界。
        assertThat(catalog.outputHoldCost(MODEL, 1_000_000, offPeak).orElseThrow())
                .isEqualByComparingTo("16.00000000");
        assertThat(catalog.outputHoldCost("unlisted-model", 1_000_000, offPeak)).isEmpty();
    }
}
