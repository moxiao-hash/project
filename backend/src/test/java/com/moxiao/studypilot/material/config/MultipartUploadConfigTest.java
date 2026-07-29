package com.moxiao.studypilot.material.config;

import jakarta.servlet.MultipartConfigElement;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MultipartUploadConfigTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(MultipartUploadConfig.class);

    @Test
    void defaultsAllowTwentyMegabyteFileAndMultipartOverhead() {
        contextRunner.run(context -> {
            MultipartConfigElement config = context.getBean(MultipartConfigElement.class);
            assertEquals(20L * 1024 * 1024, config.getMaxFileSize());
            assertEquals(25L * 1024 * 1024, config.getMaxRequestSize());
        });
    }

    @Test
    void standardSpringMultipartPropertiesOverrideDefaults() {
        contextRunner
                .withPropertyValues(
                        "spring.servlet.multipart.max-file-size=7MB",
                        "spring.servlet.multipart.max-request-size=9MB"
                )
                .run(context -> {
                    MultipartConfigElement config =
                            context.getBean(MultipartConfigElement.class);
                    assertEquals(7L * 1024 * 1024, config.getMaxFileSize());
                    assertEquals(9L * 1024 * 1024, config.getMaxRequestSize());
                });
    }
}
