package com.moxiao.studypilot.agent.usage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

/**
 * 模型预算的部署级配置。
 *
 * <p>预算的"每日"必须落在显式配置的时区里；绝不使用 {@link ZoneId#systemDefault()}
 * 推断，否则同一份数据在不同服务器上会被切到不同的自然日。默认
 * {@code Asia/Shanghai}。</p>
 */
@Component
@ConfigurationProperties(prefix = "studypilot.assistant.budget")
public class AssistantBudgetProperties {

    private String timezone = "Asia/Shanghai";

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    /** 解析配置的时区；非法值在应用启动装配第一个 bean 时立即失败。 */
    public ZoneId zoneId() {
        return ZoneId.of(timezone);
    }
}
