package com.moxiao.studypilot.agent.developer;

import org.springframework.stereotype.Service;

/**
 * Task 33：本地界面兜底动作的 Java 执行入口。
 *
 * <p>执行路径只接受白名单内的 channel/action/targetKey；目标必须来自 Java 静态注册表，
 * 绝不把模型提供的任意目标透传给本地服务。“业务 API 优先”在调用本地适配器之前判定：
 * {@code businessApiAvailable=true} 时只返回 {@code BUSINESS_API} 决策，不触碰适配器。</p>
 *
 * <p>失败与拒绝回执一律抛出确定性异常，交由既有治理路径记录为失败，绝不写成成功。</p>
 */
@Service
public class LocalInterfaceFallbackService {

    private final InterfaceFallbackPolicy policy;
    private final LocalAutomationClient localAutomationClient;

    public LocalInterfaceFallbackService(
            InterfaceFallbackPolicy policy,
            LocalAutomationClient localAutomationClient
    ) {
        this.policy = policy;
        this.localAutomationClient = localAutomationClient;
    }

    /** 打开动作前的前置校验：不产生任何副作用，也不连接本地服务。 */
    public void validate(
            boolean businessApiAvailable, String channel, String actionKey, String targetKey
    ) {
        policy.preview(businessApiAvailable, channel, actionKey, targetKey);
    }

    public LocalInterfaceExecutionResult execute(
            String ownerId,
            boolean businessApiAvailable,
            String channel,
            String actionKey,
            String targetKey
    ) {
        InterfaceFallbackDecision decision = policy.preview(
                businessApiAvailable, channel, actionKey, targetKey);
        if (!decision.fallbackRequired()) {
            return LocalInterfaceExecutionResult.businessApiPreferred(decision);
        }
        LocalAutomationReceipt receipt;
        try {
            receipt = localAutomationClient.invoke(
                    decision.channel(), LocalInterfaceAction.valueOf(decision.actionKey()),
                    decision.targetKey(), ownerId);
        } catch (LocalAutomationException exception) {
            throw new LocalAutomationException(exception.errorCode(),
                    describe(exception.errorCode(), exception.getMessage()));
        }
        if (!receipt.succeeded()) {
            throw failure(receipt);
        }
        return LocalInterfaceExecutionResult.executed(decision, receipt);
    }

    private static LocalAutomationException failure(LocalAutomationReceipt receipt) {
        String code = receipt.errorCode() == null
                ? "LOCAL_ADAPTER_" + receipt.status().name() : receipt.errorCode();
        return new LocalAutomationException(code, describe(code, receipt.message()));
    }

    /** 稳定错误码必须出现在用户可见错误里，同时保留人工恢复提示。 */
    private static String describe(String code, String message) {
        String detail = message == null || message.isBlank() ? "" : " " + message;
        return "本地界面适配器未执行该操作 [" + code + "]" + detail + "；请手动打开目标后重试";
    }
}
