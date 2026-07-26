package com.moxiao.studypilot.material.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

@Component
public class LocalMaterialStorage {

    private final Path root;

    public LocalMaterialStorage(
            @Value("${studypilot.material.storage-root:./data/materials}") String root
    ) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    public StoredContent store(String ownerId, String filename, byte[] content) {
        try {
            if (content.length > 20L * 1024 * 1024) {
                throw new IllegalArgumentException("单个资料文件不能超过 20 MB");
            }
            String safeSuffix = suffix(filename);
            String key = ownerId + "/" + UUID.randomUUID() + safeSuffix;
            Path target = resolveKey(key);
            Files.createDirectories(target.getParent());
            Files.write(target, content, StandardOpenOption.CREATE_NEW);
            return new StoredContent(key, content.length);
        } catch (IOException exception) {
            throw new IllegalStateException("资料文件保存失败", exception);
        }
    }

    public Resource load(String key) {
        try {
            Path target = resolveKey(key);
            if (!Files.isRegularFile(target)) {
                throw new IllegalArgumentException("资料原始内容不存在");
            }
            return new UrlResource(target.toUri());
        } catch (IOException exception) {
            throw new IllegalStateException("资料文件读取失败", exception);
        }
    }

    private Path resolveKey(String key) {
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法资料存储路径");
        }
        return target;
    }

    private String suffix(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot).toLowerCase();
    }

    public record StoredContent(String key, long length) {
    }
}
