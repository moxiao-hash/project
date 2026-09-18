package com.moxiao.studypilot.agent.usage;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AssistantUsageConfiguration {

    /**
     * 官方价格目录 bean：由 {@code studypilot.assistant.pricing.*} 注入。
     * 配置缺失时目录为空，全部按"不可估算"处理，绝不按零成本计费。
     */
    @Bean
    public ModelPricingCatalog modelPricingCatalog(AssistantPricingProperties properties) {
        return properties.toCatalog();
    }
}
