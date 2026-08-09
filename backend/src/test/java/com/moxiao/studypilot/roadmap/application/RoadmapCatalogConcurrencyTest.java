package com.moxiao.studypilot.roadmap.application;

import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodeJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapNodePrerequisiteJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapStageJpaRepository;
import com.moxiao.studypilot.roadmap.infrastructure.RoadmapTemplateJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Timeout(value = 30, unit = TimeUnit.SECONDS)
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

    @Test
    void concurrentImportersWithSameChecksumAllSucceed() throws Exception {
        int workers = 6;
        CountDownLatch start = new CountDownLatch(1);
        CyclicBarrier afterMissingQuery = new CyclicBarrier(workers);
        RoadmapCatalogImporter importer = importer(afterMissingQuery, UnaryOperator.identity());
        executor = Executors.newFixedThreadPool(workers);

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < workers; index++) {
                futures.add(executor.submit(() -> {
                    await(start);
                    importer.importCatalog();
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                getWithinDeadline(future);
            }

            assertCatalogCounts();
        } finally {
            shutdownExecutor();
        }
    }

    @Test
    void concurrentImportersWithDifferentChecksumsRejectLoserAsImmutable() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        CyclicBarrier afterMissingQuery = new CyclicBarrier(2);
        RoadmapCatalogImporter original = importer(afterMissingQuery, UnaryOperator.identity());
        RoadmapCatalogImporter changed = importer(afterMissingQuery, ignored -> "f".repeat(64));
        executor = Executors.newFixedThreadPool(2);

        try {
            List<Future<?>> futures = List.of(
                    executor.submit(() -> { await(start); original.importCatalog(); }),
                    executor.submit(() -> { await(start); changed.importCatalog(); })
            );
            start.countDown();

            int successes = 0;
            List<Throwable> failures = new ArrayList<>();
            for (Future<?> future : futures) {
                try {
                    getWithinDeadline(future);
                    successes++;
                } catch (ExecutionException exception) {
                    failures.add(exception.getCause());
                }
            }

            assertThat(successes).isEqualTo(1);
            assertThat(failures).singleElement().isInstanceOf(IllegalStateException.class);
            assertThat(failures.get(0).getMessage()).isEqualTo("已发布路线版本不可修改");
            assertCatalogCounts();
        } finally {
            shutdownExecutor();
        }
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
            barrier.await(10, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            throw new AssertionError("并发导入线程未在 10 秒内抵达碰撞点", exception);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void getWithinDeadline(Future<?> future) throws ExecutionException, InterruptedException {
        try {
            future.get(20, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            throw new AssertionError("并发导入未在 20 秒内完成", exception);
        }
    }

    private void shutdownExecutor() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new AssertionError("并发测试线程池未在 5 秒内终止");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待并发测试线程池终止时被中断", exception);
        }
    }
}
