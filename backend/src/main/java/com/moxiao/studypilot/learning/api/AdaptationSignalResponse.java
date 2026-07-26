package com.moxiao.studypilot.learning.api;

import com.moxiao.studypilot.learning.domain.AdaptationSignalType;

public record AdaptationSignalResponse(
        AdaptationSignalType type,
        int count,
        Double deviationRatio
) {
}
