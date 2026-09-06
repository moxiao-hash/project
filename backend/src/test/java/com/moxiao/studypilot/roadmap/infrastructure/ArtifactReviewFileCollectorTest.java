package com.moxiao.studypilot.roadmap.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactReviewFileCollectorTest {

    @TempDir
    Path tempDir;

    @Test
    void buildsImmutableManifestWhileExcludingSecretsAndBuildOutputs() throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java/example"));
        Files.writeString(
                tempDir.resolve("src/main/java/example/App.java"),
                "package example; public class App {}"
        );
        Files.createDirectories(tempDir.resolve("src/main/resources"));
        Files.writeString(
                tempDir.resolve("src/main/resources/application.properties"),
                "spring.datasource.password=secret"
        );
        Files.writeString(tempDir.resolve(".env"), "DEEPSEEK_API_KEY=secret");
        Files.createDirectories(tempDir.resolve("target"));
        Files.writeString(tempDir.resolve("target/result.txt"), "generated");

        ArtifactReviewFileCollector.CollectionResult result =
                new ArtifactReviewFileCollector().collect(tempDir);

        assertThat(result.passed()).isTrue();
        assertThat(result.files())
                .extracting(ArtifactReviewFileCollector.FileSnapshot::relativePath)
                .containsExactly("src/main/java/example/App.java");
        assertThat(result.files().get(0).sha256()).hasSize(64);
        assertThat(result.excludedPaths()).contains(
                ".env",
                "src/main/resources/application.properties"
        );
    }

    @Test
    void blocksReviewWhenAnIncludedSourceContainsCredentialMaterial() throws Exception {
        Files.writeString(
                tempDir.resolve("Unsafe.java"),
                "String apiKey = \"sk-1234567890abcdefghijklmnop\";"
        );

        ArtifactReviewFileCollector.CollectionResult result =
                new ArtifactReviewFileCollector().collect(tempDir);

        assertThat(result.passed()).isFalse();
        assertThat(result.findings()).contains("Unsafe.java");
        assertThat(result.files()).isEmpty();
    }

    @Test
    void acceptsASingleSubmittedSourceFile() throws Exception {
        Path source = tempDir.resolve("Solution.java");
        Files.writeString(source, "public class Solution {}");

        ArtifactReviewFileCollector.CollectionResult result =
                new ArtifactReviewFileCollector().collect(source);

        assertThat(result.passed()).isTrue();
        assertThat(result.files())
                .extracting(ArtifactReviewFileCollector.FileSnapshot::relativePath)
                .containsExactly("Solution.java");
    }
}
