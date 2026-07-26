package com.moxiao.studypilot.material.application;

import com.moxiao.studypilot.material.api.CreateWebMaterialRequest;
import com.moxiao.studypilot.material.api.CreateWebSearchRequest;
import com.moxiao.studypilot.material.api.ImportWebSearchResultRequest;
import com.moxiao.studypilot.material.api.WebSearchResponse;
import com.moxiao.studypilot.material.infrastructure.MaterialEntity;
import com.moxiao.studypilot.material.infrastructure.MaterialJpaRepository;
import com.moxiao.studypilot.material.infrastructure.WebSearchResultEntity;
import com.moxiao.studypilot.material.infrastructure.WebSearchResultJpaRepository;
import com.moxiao.studypilot.material.infrastructure.WebSearchSessionEntity;
import com.moxiao.studypilot.material.infrastructure.WebSearchSessionJpaRepository;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class WebSearchSourceService {

    private final WebSearchSessionJpaRepository sessionRepository;
    private final WebSearchResultJpaRepository resultRepository;
    private final MaterialJpaRepository materialRepository;
    private final MaterialService materialService;

    public WebSearchSourceService(
            WebSearchSessionJpaRepository sessionRepository,
            WebSearchResultJpaRepository resultRepository,
            MaterialJpaRepository materialRepository,
            MaterialService materialService
    ) {
        this.sessionRepository = sessionRepository;
        this.resultRepository = resultRepository;
        this.materialRepository = materialRepository;
        this.materialService = materialService;
    }

    @Transactional
    public WebSearchResponse record(CreateWebSearchRequest request) {
        WebSearchSessionEntity session = sessionRepository.save(
                new WebSearchSessionEntity(
                        UUID.randomUUID().toString(),
                        request.ownerId(),
                        request.query().trim(),
                        request.providerRequestId(),
                        Instant.now()
                )
        );
        List<WebSearchResultEntity> results = resultRepository.saveAll(
                request.results().stream()
                        .map(result -> new WebSearchResultEntity(
                                UUID.randomUUID().toString(),
                                session.getId(),
                                request.ownerId(),
                                result.title().trim(),
                                result.url().trim(),
                                result.snippet() == null ? "" : result.snippet(),
                                result.score()
                        ))
                        .toList()
        );
        return WebSearchResponse.from(session, results);
    }

    @Transactional
    public MaterialEntity importResult(
            String ownerId,
            String resultId,
            ImportWebSearchResultRequest request
    ) {
        WebSearchResultEntity result = resultRepository
                .findOwnedForUpdate(resultId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("联网搜索结果不存在"));
        if (result.getImportedMaterialId() != null) {
            return materialRepository.findByIdAndOwnerId(
                            result.getImportedMaterialId(),
                            ownerId
                    )
                    .orElseThrow(() -> new ResourceNotFoundException("已导入资料不存在"));
        }
        MaterialEntity material = materialService.createWeb(
                ownerId,
                new CreateWebMaterialRequest(
                        result.getTitle(),
                        result.getUrl(),
                        request.category(),
                        request.privacyLevel()
                )
        );
        result.markImported(material.getId());
        return material;
    }
}
