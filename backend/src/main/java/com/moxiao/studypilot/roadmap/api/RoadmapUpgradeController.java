package com.moxiao.studypilot.roadmap.api;

import com.moxiao.studypilot.auth.security.AuthenticatedUser;
import com.moxiao.studypilot.roadmap.application.RoadmapUpgradeService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/roadmaps/current/upgrades")
public class RoadmapUpgradeController {

    private final RoadmapUpgradeService upgradeService;

    public RoadmapUpgradeController(RoadmapUpgradeService upgradeService) {
        this.upgradeService = upgradeService;
    }

    @GetMapping
    public List<RoadmapUpgradeResponse> previews(
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return upgradeService.previews(user.id());
    }

    @PostMapping("/{upgradeId}/confirm")
    public RoadmapUpgradeResponse confirm(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String upgradeId
    ) {
        return upgradeService.confirm(user.id(), upgradeId);
    }
}
