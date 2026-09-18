package com.moxiao.studypilot.agent.usage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 官方价格目录配置。
 *
 * <p>数值必须来自 DeepSeek 官方定价页当前版本，并在填写时同时写 {@code version} 与
 * {@code effective-from}。未配置或配置不完整的模型一律按"不可估算"处理，不会写零。</p>
 */
@Component
@ConfigurationProperties(prefix = "studypilot.assistant.pricing")
public class AssistantPricingProperties {

    private String version;
    private LocalDate effectiveFrom;
    private String currency = "CNY";
    private Map<String, ModelPrice> models = new LinkedHashMap<>();

    public static class ModelPrice {

        private BigDecimal inputPerMillion;
        private BigDecimal cachedInputPerMillion;
        private BigDecimal outputPerMillion;

        public BigDecimal getInputPerMillion() {
            return inputPerMillion;
        }

        public void setInputPerMillion(BigDecimal inputPerMillion) {
            this.inputPerMillion = inputPerMillion;
        }

        public BigDecimal getCachedInputPerMillion() {
            return cachedInputPerMillion;
        }

        public void setCachedInputPerMillion(BigDecimal cachedInputPerMillion) {
            this.cachedInputPerMillion = cachedInputPerMillion;
        }

        public BigDecimal getOutputPerMillion() {
            return outputPerMillion;
        }

        public void setOutputPerMillion(BigDecimal outputPerMillion) {
            this.outputPerMillion = outputPerMillion;
        }

        boolean isComplete() {
            return inputPerMillion != null && cachedInputPerMillion != null && outputPerMillion != null;
        }
    }

    public ModelPricingCatalog toCatalog() {
        Map<String, ModelPricingCatalog.PriceSpec> specs = new LinkedHashMap<>();
        models.forEach((modelName, price) -> {
            if (price == null || !price.isComplete()) {
                return;
            }
            specs.put(modelName, new ModelPricingCatalog.PriceSpec(
                    version, effectiveFrom,
                    price.getInputPerMillion(), price.getCachedInputPerMillion(),
                    price.getOutputPerMillion(), currency));
        });
        return ModelPricingCatalog.of(specs);
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(LocalDate effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Map<String, ModelPrice> getModels() {
        return models;
    }

    public void setModels(Map<String, ModelPrice> models) {
        this.models = models;
    }
}
