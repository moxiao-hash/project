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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.moxiao.studypilot.material.domain.MaterialCategory;
import com.moxiao.studypilot.user.domain.PrivacyLevel;

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

    @PostMapping("/text")
    @ResponseStatus(HttpStatus.CREATED)
    public MaterialResponse createText(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateTextMaterialRequest request
    ) {
        return MaterialResponse.from(service.createText(user.id(), request));
    }

    @PostMapping("/web")
    @ResponseStatus(HttpStatus.CREATED)
    public MaterialResponse createWeb(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateWebMaterialRequest request
    ) {
        return MaterialResponse.from(service.createWeb(user.id(), request));
    }

    @PostMapping(path = "/files", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public MaterialResponse createFile(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam String title,
            @RequestParam MaterialCategory category,
            @RequestParam PrivacyLevel privacyLevel,
            @RequestParam MultipartFile file
    ) {
        return MaterialResponse.from(
                service.createFile(user.id(), title, category, privacyLevel, file)
        );
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
