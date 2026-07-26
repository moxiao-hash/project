package com.moxiao.studypilot.material.api;

import com.moxiao.studypilot.auth.security.AuthenticatedUser;
import com.moxiao.studypilot.material.application.WebSearchSourceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/web-search-results")
public class WebSearchResultController {

    private final WebSearchSourceService service;

    public WebSearchResultController(WebSearchSourceService service) {
        this.service = service;
    }

    @PostMapping("/{resultId}/import")
    @ResponseStatus(HttpStatus.CREATED)
    public MaterialResponse importResult(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String resultId,
            @Valid @RequestBody ImportWebSearchResultRequest request
    ) {
        return MaterialResponse.from(service.importResult(user.id(), resultId, request));
    }
}
