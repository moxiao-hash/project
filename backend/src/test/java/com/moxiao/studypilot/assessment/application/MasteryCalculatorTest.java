package com.moxiao.studypilot.assessment.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MasteryCalculatorTest {

    @Test
    void appliesRecentWeightedEwma() {
        assertThat(MasteryCalculator.updateComponent(50.0, 100.0, 1.0))
                .isEqualTo(70.0);
        assertThat(MasteryCalculator.updateComponent(50.0, 100.0, 0.3))
                .isEqualTo(56.0);
    }

    @Test
    void normalizesWeightsWhenSignalsAreMissing() {
        assertThat(MasteryCalculator.combined(70.0, 100.0, null))
                .isCloseTo(74.736842, org.assertj.core.data.Offset.offset(0.000001));
        assertThat(MasteryCalculator.combined(70.0, 100.0, 0.0))
                .isEqualTo(71.0);
    }
}
