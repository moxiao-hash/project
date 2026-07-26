package com.moxiao.studypilot.learning.api;

import java.time.LocalDate;

/**
 * Python 每 15 分钟查询一次；Java 只返回在用户本地日期中尚未分析的候选。
 */
public record NightlyAdjustmentCandidateResponse(
        String ownerId,
        LocalDate analysisDate
) {
}
