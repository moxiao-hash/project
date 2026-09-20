package com.moxiao.studypilot.agent.usage;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AssistantUsageConfiguration {

    /**
     * 官方价格目录 bean：内置官方定价页核对过的版本化单价。
     * 未列入目录的模型按"不可估算"处理，绝不按零成本计费。
     */
    @Bean
    public ModelPricingCatalog modelPricingCatalog() {
        return ModelPricingCatalog.official();
    }
}
