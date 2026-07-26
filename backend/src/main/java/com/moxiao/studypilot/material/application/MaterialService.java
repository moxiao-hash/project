package com.moxiao.studypilot.material.application;

import com.moxiao.studypilot.material.api.CreateMaterialRequest;
import com.moxiao.studypilot.material.api.CreateTextMaterialRequest;
import com.moxiao.studypilot.material.api.CreateWebMaterialRequest;
import com.moxiao.studypilot.material.api.UpdateMaterialProcessingRequest;
import com.moxiao.studypilot.material.domain.MaterialCategory;
import com.moxiao.studypilot.material.domain.MaterialType;
import com.moxiao.studypilot.material.infrastructure.MaterialEntity;
import com.moxiao.studypilot.material.infrastructure.MaterialJpaRepository;
import com.moxiao.studypilot.material.infrastructure.MaterialChunkEntity;
import com.moxiao.studypilot.material.infrastructure.MaterialChunkJpaRepository;
import com.moxiao.studypilot.material.infrastructure.MaterialProcessingJobEntity;
import com.moxiao.studypilot.material.infrastructure.MaterialProcessingJobJpaRepository;
import com.moxiao.studypilot.material.infrastructure.LocalMaterialStorage;
import com.moxiao.studypilot.shared.error.ResourceNotFoundException;
import com.moxiao.studypilot.user.domain.PrivacyLevel;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class MaterialService {

    private final MaterialJpaRepository repository;
    private final MaterialProcessingJobJpaRepository jobRepository;
    private final LocalMaterialStorage storage;
    private final MaterialChunkJpaRepository chunkRepository;

    public MaterialService(
            MaterialJpaRepository repository,
            MaterialProcessingJobJpaRepository jobRepository,
            LocalMaterialStorage storage,
            MaterialChunkJpaRepository chunkRepository
    ) {
        this.repository = repository;
        this.jobRepository = jobRepository;
        this.storage = storage;
        this.chunkRepository = chunkRepository;
    }

    @Transactional
    public MaterialEntity create(String ownerId, CreateMaterialRequest request) {
        MaterialEntity material = repository.save(new MaterialEntity(
                UUID.randomUUID().toString(),
                ownerId,
                request.title().trim(),
                request.materialType(),
                request.category(),
                request.privacyLevel(),
                request.sourceUrl(),
                Instant.now()
        ));
        return material;
    }

    @Transactional
    public MaterialEntity createText(String ownerId, CreateTextMaterialRequest request) {
        MaterialEntity material = new MaterialEntity(
                UUID.randomUUID().toString(),
                ownerId,
                request.title().trim(),
                MaterialType.TEXT,
                request.category(),
                request.privacyLevel(),
                null,
                Instant.now()
        );
        byte[] bytes = request.content().getBytes(StandardCharsets.UTF_8);
        LocalMaterialStorage.StoredContent stored = storage.store(
                ownerId,
                material.getId() + ".txt",
                bytes
        );
        material.attachStoredContent(
                "pasted-text.txt",
                stored.key(),
                MediaTypeNames.TEXT,
                stored.length(),
                Instant.now()
        );
        repository.save(material);
        createJob(material);
        return material;
    }

    @Transactional
    public MaterialEntity createFile(
            String ownerId,
            String title,
            MaterialCategory category,
            PrivacyLevel privacyLevel,
            MultipartFile file
    ) {
        if (title == null || title.isBlank() || title.length() > 180) {
            throw new IllegalArgumentException("资料标题不能为空且不能超过 180 个字符");
        }
        String filename = file.getOriginalFilename();
        MaterialType type = materialTypeFor(filename);
        try {
            byte[] bytes = file.getBytes();
            LocalMaterialStorage.StoredContent stored = storage.store(ownerId, filename, bytes);
            MaterialEntity material = new MaterialEntity(
                    UUID.randomUUID().toString(),
                    ownerId,
                    title.trim(),
                    type,
                    category,
                    privacyLevel,
                    null,
                    Instant.now()
            );
            material.attachStoredContent(
                    filename,
                    stored.key(),
                    file.getContentType(),
                    stored.length(),
                    Instant.now()
            );
            repository.save(material);
            createJob(material);
            return material;
        } catch (IOException exception) {
            throw new IllegalStateException("资料文件读取失败", exception);
        }
    }

    @Transactional
    public MaterialEntity createWeb(String ownerId, CreateWebMaterialRequest request) {
        String url = validatePublicUrl(request.url());
        MaterialEntity material = repository.save(new MaterialEntity(
                UUID.randomUUID().toString(),
                ownerId,
                request.title().trim(),
                MaterialType.WEB_PAGE,
                request.category(),
                request.privacyLevel(),
                url,
                Instant.now()
        ));
        createJob(material);
        return material;
    }

    @Transactional(readOnly = true)
    public List<MaterialEntity> list(String ownerId) {
        return repository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId);
    }

    @Transactional(readOnly = true)
    public MaterialEntity get(String ownerId, String materialId) {
        return repository.findByIdAndOwnerId(materialId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("学习资料不存在"));
    }

    @Transactional(readOnly = true)
    public MaterialEntity getInternal(String materialId) {
        return repository.findById(materialId)
                .orElseThrow(() -> new ResourceNotFoundException("学习资料不存在"));
    }

    @Transactional(readOnly = true)
    public Resource loadContent(String materialId) {
        MaterialEntity material = getInternal(materialId);
        if (material.getStorageKey() == null) {
            throw new IllegalArgumentException("网页资料需要由处理器安全抓取");
        }
        return storage.load(material.getStorageKey());
    }

    @Transactional(readOnly = true)
    public List<MaterialChunkEntity> chunks(String materialId) {
        getInternal(materialId);
        return chunkRepository.findAllByMaterialIdOrderByPosition(materialId);
    }

    @Transactional
    public MaterialEntity updateProcessing(
            String materialId,
            UpdateMaterialProcessingRequest request
    ) {
        MaterialEntity material = repository.findById(materialId)
                .orElseThrow(() -> new ResourceNotFoundException("学习资料不存在"));
        material.updateProcessingResult(
                request.status(),
                request.summary(),
                request.tags(),
                request.knowledgePoints(),
                request.contentReference(),
                request.failureReason(),
                Instant.now()
        );
        return material;
    }

    private void createJob(MaterialEntity material) {
        jobRepository.save(new MaterialProcessingJobEntity(
                UUID.randomUUID().toString(),
                material.getId(),
                Instant.now()
        ));
    }

    private MaterialType materialTypeFor(String filename) {
        if (filename == null || !filename.contains(".")) {
            throw new IllegalArgumentException("资料文件必须包含受支持的扩展名");
        }
        String extension = filename.substring(filename.lastIndexOf('.') + 1)
                .toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "txt" -> MaterialType.TEXT;
            case "md", "markdown" -> MaterialType.MARKDOWN;
            case "pdf" -> MaterialType.PDF;
            case "docx" -> MaterialType.WORD;
            default -> throw new IllegalArgumentException(
                    "仅支持 TXT、Markdown、PDF 和 DOCX 文件"
            );
        };
    }

    private String validatePublicUrl(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl.trim());
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalArgumentException("网页地址必须是有效的 HTTP 或 HTTPS URL");
            }
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                    throw new IllegalArgumentException("网页地址不能指向本机或内网");
                }
            }
            return uri.toString();
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("网页域名无法解析", exception);
        }
    }

    private static final class MediaTypeNames {
        private static final String TEXT = "text/plain; charset=UTF-8";

        private MediaTypeNames() {
        }
    }
}
