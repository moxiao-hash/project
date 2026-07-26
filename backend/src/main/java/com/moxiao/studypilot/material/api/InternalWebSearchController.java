package com.moxiao.studypilot.material.api;

import com.moxiao.studypilot.material.application.WebSearchSourceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/web-searches")
public class InternalWebSearchController {

    private final WebSearchSourceService service;

    public InternalWebSearchController(WebSearchSourceService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WebSearchResponse record(@Valid @RequestBody CreateWebSearchRequest request) {
        return service.record(request);
    }
}
