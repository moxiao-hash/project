package com.moxiao.studypilot.roadmap.config;

import com.moxiao.studypilot.roadmap.application.RoadmapCatalogImporter;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RoadmapCatalogConfiguration {

    @Bean
    @ConditionalOnProperty(
            name = "studypilot.roadmap.catalog-import-enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    ApplicationRunner roadmapCatalogRunner(RoadmapCatalogImporter importer) {
        return arguments -> importer.importCatalog();
    }
}
