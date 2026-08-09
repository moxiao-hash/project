package com.moxiao.studypilot.roadmap.application;

import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodePrerequisiteJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapStageJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapTemplateJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RoadmapCatalogConcurrencyTest {

    @Autowired RoadmapTemplateJpaRepository templateRepository;
    @Autowired RoadmapStageJpaRepository stageRepository;
    @Autowired RoadmapNodeJpaRepository nodeRepository;
    @Autowired RoadmapNodePrerequisiteJpaRepository prerequisiteRepository;
    @Autowired ObjectMapper objectMapper;
    @Autowired PlatformTransactionManager transactionManager;

    private ExecutorService executor;

    @BeforeEach
    void cleanCatalog() {
        prerequisiteRepository.deleteAll();
        nodeRepository.deleteAll();
        stageRepository.deleteAll();
        templateRepository.deleteAll();
    }

    @AfterEach
    void stopExecutor() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentImportersWithSameChecksumAllSucceed() throws Exception {
        int workers = 6;
        CountDownLatch start = new CountDownLatch(1);
        CyclicBarrier afterMissingQuery = new CyclicBarrier(workers);
        RoadmapCatalogImporter importer = importer(afterMissingQuery, UnaryOperator.identity());
        executor = Executors.newFixedThreadPool(workers);

        List<Future<?>> futures = new ArrayList<>();
        for (int index = 0; index < workers; index++) {
            futures.add(executor.submit(() -> {
                await(start);
                importer.importCatalog();
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get();
        }

        assertCatalogCounts();
    }

    @Test
    void concurrentImportersWithDifferentChecksumsRejectLoserAsImmutable() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        CyclicBarrier afterMissingQuery = new CyclicBarrier(2);
        RoadmapCatalogImporter original = importer(afterMissingQuery, UnaryOperator.identity());
        RoadmapCatalogImporter changed = importer(afterMissingQuery, ignored -> "f".repeat(64));
        executor = Executors.newFixedThreadPool(2);

        List<Future<?>> futures = List.of(
                executor.submit(() -> { await(start); original.importCatalog(); }),
                executor.submit(() -> { await(start); changed.importCatalog(); })
        );
        start.countDown();

        int successes = 0;
        List<Throwable> failures = new ArrayList<>();
        for (Future<?> future : futures) {
            try {
                future.get();
                successes++;
            } catch (ExecutionException exception) {
                failures.add(exception.getCause());
            }
        }

        assertThat(successes).isEqualTo(1);
        assertThat(failures).singleElement().isInstanceOf(IllegalStateException.class);
        assertThat(failures.get(0).getMessage()).isEqualTo("已发布路线版本不可修改");
        assertCatalogCounts();
    }

    private RoadmapCatalogImporter importer(
            CyclicBarrier afterMissingQuery,
            UnaryOperator<String> checksumTransform
    ) {
        return new RoadmapCatalogImporter(
                templateRepository,
                stageRepository,
                nodeRepository,
                prerequisiteRepository,
                objectMapper,
                transactionManager,
                () -> await(afterMissingQuery),
                checksumTransform
        );
    }

    private void assertCatalogCounts() {
        assertThat(templateRepository.count()).isEqualTo(1);
        assertThat(stageRepository.count()).isEqualTo(12);
        assertThat(nodeRepository.count()).isEqualTo(64);
        assertThat(prerequisiteRepository.count()).isEqualTo(79);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
