package com.moxiao.studypilot.material.api;

import com.moxiao.studypilot.material.application.MaterialService;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/materials")
public class InternalMaterialController {

    private final MaterialService service;

    public InternalMaterialController(MaterialService service) {
        this.service = service;
    }

    @PatchMapping("/{materialId}/processing")
    public MaterialResponse updateProcessing(
            @PathVariable String materialId,
            @Valid @RequestBody UpdateMaterialProcessingRequest request
    ) {
        return MaterialResponse.from(service.updateProcessing(materialId, request));
    }

    @GetMapping("/{materialId}/content")
    public ResponseEntity<Resource> content(@PathVariable String materialId) {
        String mediaType = service.getInternal(materialId).getMediaType();
        return ResponseEntity.ok()
                .header(
                        "Content-Type",
                        mediaType == null ? "application/octet-stream" : mediaType
                )
                .body(service.loadContent(materialId));
    }
}
