package com.moxiao.studypilot.roadmap.api;

import java.util.List;

public record RoadmapUpgradeResponse(
        String id,
        int sourceVersion,
        int targetVersion,
        String status,
        List<String> unchangedNodeCodes,
        List<String> addedNodeCodes,
        List<String> removedNodeCodes,
        List<String> manualReviewNodeCodes
) { }
