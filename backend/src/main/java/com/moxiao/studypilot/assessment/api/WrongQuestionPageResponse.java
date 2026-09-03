package com.moxiao.studypilot.assessment.api;

import java.util.List;

public record WrongQuestionPageResponse(
        List<WrongQuestionResponse> items,
        long totalElements,
        int page,
        int size
) {
}
