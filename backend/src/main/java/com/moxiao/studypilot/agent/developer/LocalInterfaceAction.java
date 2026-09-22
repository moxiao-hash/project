package com.moxiao.studypilot.agent.developer;

/**
 * Task 33 冻结契约中唯一允许的六个本地界面动作。
 *
 * <p>每个动作只属于一个通道；不存在通用 click / type / press / navigate / open-path /
 * script / shell 动作。旧动作名（OPEN_LOGIN 等）已彻底移除，不得作为别名复活。</p>
 */
public enum LocalInterfaceAction {
    OPEN_STUDYPILOT_ROUTE(InterfaceAutomationChannel.PLAYWRIGHT_DOM),
    FOCUS_AGENT_INPUT(InterfaceAutomationChannel.PLAYWRIGHT_DOM),
    OPEN_RESULT_PANEL(InterfaceAutomationChannel.PLAYWRIGHT_DOM),
    OPEN_REGISTERED_FILE(InterfaceAutomationChannel.IDEA_ACCESSIBILITY),
    FOCUS_RUN_CONFIGURATION(InterfaceAutomationChannel.IDEA_ACCESSIBILITY),
    SHOW_TEST_RESULT(InterfaceAutomationChannel.IDEA_ACCESSIBILITY);

    private final InterfaceAutomationChannel channel;

    LocalInterfaceAction(InterfaceAutomationChannel channel) {
        this.channel = channel;
    }

    public InterfaceAutomationChannel channel() {
        return channel;
    }
}
