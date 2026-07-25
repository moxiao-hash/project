package com.moxiao.studypilot.material.api;

import com.moxiao.studypilot.auth.security.AuthenticatedUser;
import com.moxiao.studypilot.material.application.MaterialService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/materials")
public class MaterialController {

    private final MaterialService service;

    public MaterialController(MaterialService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MaterialResponse create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateMaterialRequest request
    ) {
        return MaterialResponse.from(service.create(user.id(), request));
    }

    @GetMapping
    public List<MaterialResponse> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.list(user.id()).stream().map(MaterialResponse::from).toList();
    }

    @GetMapping("/{materialId}")
    public MaterialResponse get(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String materialId
    ) {
        return MaterialResponse.from(service.get(user.id(), materialId));
    }
}
