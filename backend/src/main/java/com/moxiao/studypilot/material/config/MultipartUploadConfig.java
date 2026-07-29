package com.moxiao.studypilot.material.config;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * 统一本地 IDEA 与容器环境的 multipart 入口上限。
 *
 * <p>Servlet 层允许 20 MB 文件，并为 multipart 边界和其他表单字段预留 5 MB。
 * {@code LocalMaterialStorage} 仍会执行最终的 20 MB 业务校验。使用 Spring 标准
 * 属性名，因此也可通过对应环境变量覆盖。</p>
 */
@Configuration(proxyBeanMethods = false)
public class MultipartUploadConfig {

    @Bean
    @ConditionalOnMissingBean(MultipartConfigElement.class)
    MultipartConfigElement multipartConfigElement(
            @Value("${spring.servlet.multipart.max-file-size:20MB}")
            String maxFileSize,
            @Value("${spring.servlet.multipart.max-request-size:25MB}")
            String maxRequestSize
    ) {
        MultipartConfigFactory factory = new MultipartConfigFactory();
        factory.setMaxFileSize(DataSize.parse(maxFileSize));
        factory.setMaxRequestSize(DataSize.parse(maxRequestSize));
        return factory.createMultipartConfig();
    }
}
