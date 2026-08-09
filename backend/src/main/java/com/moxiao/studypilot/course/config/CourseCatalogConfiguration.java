package com.moxiao.studypilot.course.config;

import com.moxiao.studypilot.course.application.CourseCatalogImporter;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

@Configuration
public class CourseCatalogConfiguration {

    @Bean
    @Order(10)
    @ConditionalOnProperty(
            name = "studypilot.course.catalog-import-enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    ApplicationRunner courseCatalogRunner(CourseCatalogImporter importer) {
        return arguments -> importer.importCatalog();
    }
}
