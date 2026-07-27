package com.moxiao.studypilot.assessment.application;

/**
 * 掌握度纯计算规则。
 *
 * <p>独立成无状态类后，EWMA 和缺省权重归一化可以在不启动 Spring 的情况下验证。</p>
 */
public final class MasteryCalculator {
    private static final double ALPHA = 0.4;

    private MasteryCalculator() {
    }

    public static double updateComponent(
            Double oldComponent,
            double latestEvidence,
            double evidenceWeight
    ) {
        if (oldComponent == null) {
            return latestEvidence;
        }
        double effectiveAlpha = ALPHA * evidenceWeight;
        return oldComponent * (1 - effectiveAlpha) + latestEvidence * effectiveAlpha;
    }

    public static double combined(Double quiz, Double task, Double selfAssessment) {
        double weighted = 0;
        double availableWeight = 0;
        if (quiz != null) {
            weighted += quiz * 0.80;
            availableWeight += 0.80;
        }
        if (task != null) {
            weighted += task * 0.15;
            availableWeight += 0.15;
        }
        if (selfAssessment != null) {
            weighted += selfAssessment * 0.05;
            availableWeight += 0.05;
        }
        return availableWeight == 0 ? 0 : weighted / availableWeight;
    }
}
