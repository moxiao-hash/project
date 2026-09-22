package com.moxiao.studypilot.agent.developer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Task 33：本地界面动作的静态注册表。
 *
 * <p>浏览器通道的三个动作及其符号目标由冻结契约写死在代码里，配置无法放宽；
 * IDE 通道的文件/运行配置/测试结果句柄必须在本地配置时登记，未登记时一律失败关闭。
 * 调用方只能提交符号键，注册表永不接受路径、URL、选择器或任意文本。</p>
 */
@Component
public class LocalInterfaceRegistry {

    /** 冻结契约 §4 的浏览器符号目标，配置不可覆盖。 */
    private static final Map<LocalInterfaceAction, Set<String>> FROZEN_BROWSER_TARGETS = Map.of(
            LocalInterfaceAction.OPEN_STUDYPILOT_ROUTE,
            Set.of("ASSISTANT", "ASSISTANT_HEALTH", "WORKSPACE_ARTIFACTS"),
            LocalInterfaceAction.FOCUS_AGENT_INPUT,
            Set.of("ASSISTANT_INPUT"),
            LocalInterfaceAction.OPEN_RESULT_PANEL,
            Set.of("WORKSPACE_RESULTS"));

    private final Map<LocalInterfaceAction, Set<String>> ideTargets;

    public LocalInterfaceRegistry(
            @Value("${studypilot.local-automation.ide-targets:}") String configuredIdeTargets
    ) {
        this.ideTargets = parseIdeTargets(configuredIdeTargets);
    }

    /**
     * 测试与显式装配入口：IDE 目标仍走同一套严格解析；浏览器目标固定不变，
     * 因此传入浏览器动作不会放宽任何白名单。
     */
    public static LocalInterfaceRegistry forTesting(
            Map<LocalInterfaceAction, Set<String>> ideTargets
    ) {
        List<String> entries = new ArrayList<>();
        ideTargets.forEach((action, targets) -> {
            if (action.channel() == InterfaceAutomationChannel.IDEA_ACCESSIBILITY) {
                targets.forEach(target -> entries.add(action.name() + ":" + target));
            }
        });
        return new LocalInterfaceRegistry(String.join(",", entries));
    }

    /** 只有通道、动作与符号目标的完整组合都命中注册表时才返回 {@code true}。 */
    public boolean isRegistered(
            InterfaceAutomationChannel channel, LocalInterfaceAction action, String targetKey
    ) {
        if (channel == null || action == null) {
            return false;
        }
        if (channel != action.channel() || channel == InterfaceAutomationChannel.BUSINESS_API) {
            return false;
        }
        if (!LocalAutomationSigning.isSymbolicTarget(targetKey)) {
            return false;
        }
        return allowedTargets(action).contains(targetKey);
    }

    public Set<String> allowedTargets(LocalInterfaceAction action) {
        Set<String> frozen = FROZEN_BROWSER_TARGETS.get(action);
        if (frozen != null) {
            return frozen;
        }
        return ideTargets.getOrDefault(action, Set.of());
    }

    /** 仅供运维核查：返回当前登记的 IDE 符号目标数量，不含任何本地路径或状态。 */
    public int registeredIdeTargetCount() {
        return ideTargets.values().stream().mapToInt(Set::size).sum();
    }

    private static Map<LocalInterfaceAction, Set<String>> parseIdeTargets(String configured) {
        Map<LocalInterfaceAction, Set<String>> parsed = new LinkedHashMap<>();
        if (configured == null || configured.isBlank()) {
            return parsed;
        }
        for (String entry : configured.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int separator = trimmed.indexOf(':');
            if (separator <= 0 || separator == trimmed.length() - 1) {
                throw new IllegalStateException(
                        "本地界面适配器 IDE 目标登记格式必须是 ACTION:SYMBOLIC_KEY");
            }
            LocalInterfaceAction action;
            try {
                action = LocalInterfaceAction.valueOf(trimmed.substring(0, separator).trim());
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("本地界面适配器登记了未注册的 IDE 动作");
            }
            if (action.channel() != InterfaceAutomationChannel.IDEA_ACCESSIBILITY) {
                throw new IllegalStateException("只有 IDE 通道动作可以在本地配置中登记目标");
            }
            String targetKey = trimmed.substring(separator + 1).trim();
            if (!LocalAutomationSigning.isSymbolicTarget(targetKey)) {
                throw new IllegalStateException("本地界面适配器 IDE 目标只能是符号键，不能是路径或 URL");
            }
            parsed.computeIfAbsent(action, key -> new java.util.LinkedHashSet<>()).add(targetKey);
        }
        Map<LocalInterfaceAction, Set<String>> immutable = new LinkedHashMap<>();
        parsed.forEach((action, targets) -> immutable.put(action, Set.copyOf(targets)));
        return immutable;
    }
}
