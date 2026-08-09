package com.moxiao.studypilot.roadmap.api;

import com.moxiao.studypilot.auth.security.AuthenticatedUser;
import com.moxiao.studypilot.roadmap.application.RoadmapEnrollmentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class RoadmapController {

    private final RoadmapEnrollmentService enrollmentService;

    public RoadmapController(RoadmapEnrollmentService enrollmentService) {
        this.enrollmentService = enrollmentService;
    }

    @PostMapping("/roadmap-enrollments")
    @ResponseStatus(HttpStatus.CREATED)
    public RoadmapEnrollmentResponse enroll(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateRoadmapEnrollmentRequest request
    ) {
        return enrollmentService.enroll(user.id(), request.roadmapCode(), request.templateVersion());
    }
}
