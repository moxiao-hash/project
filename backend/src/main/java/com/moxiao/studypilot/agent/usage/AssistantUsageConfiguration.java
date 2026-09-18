package com.moxiao.studypilot.agent.usage;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AssistantUsageConfiguration {

    /**
     * 官方价格目录 bean。价格数值与版本日期必须来自官方定价页当前版本；
     * 未填入前目录为空，所有调用按"不可估算"处理。
     */
    @Bean
    public ModelPricingCatalog modelPricingCatalog() {
        return ModelPricingCatalog.official();
    }
}
