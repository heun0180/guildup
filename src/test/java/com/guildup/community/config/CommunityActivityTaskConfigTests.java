package com.guildup.community.config;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.task.TaskRejectedException;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class CommunityActivityTaskConfigTests {
    @Test
    void saturationRejectsInsteadOfRunningOnTheRequestThread() throws Exception {
        var executor = new CommunityActivityTaskConfig().communityActivitySyncExecutor();
        executor.initialize();
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try {
            Runnable blocking = () -> {
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            };
            executor.execute(blocking);
            executor.execute(blocking);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> executor.execute(() -> { throw new AssertionError("ran inline"); }))
                    .isInstanceOf(TaskRejectedException.class);
            assertThat(executor.getThreadPoolExecutor().getQueue()).isEmpty();
        } finally { release.countDown(); executor.shutdown(); }
    }

    @Test
    void requestLoggingContextDoesNotLeakIntoTheNextTask() {
        var executor = new CommunityActivityTaskConfig().communityActivitySyncExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.initialize();
        var first = new AtomicReference<String>();
        var next = new AtomicReference<String>("unset");
        try {
            MDC.put("requestId", "activity-context");
            executor.execute(() -> first.set(MDC.get("requestId")));
            MDC.remove("requestId");
            await().atMost(Duration.ofSeconds(2)).until(() -> executor.getActiveCount() == 0);
            executor.execute(() -> next.set(MDC.get("requestId")));
            await().atMost(Duration.ofSeconds(2)).until(() -> executor.getActiveCount() == 0);
            assertThat(first.get()).isEqualTo("activity-context");
            assertThat(next.get()).isNull();
        } finally { MDC.remove("requestId"); executor.shutdown(); }
    }

    @Test
    void gracefulShutdownWaitsForRunningWorkWithoutInterruptingIt() throws Exception {
        var executor = new CommunityActivityTaskConfig().communityActivitySyncExecutor();
        executor.initialize();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        try (var closer = Executors.newSingleThreadExecutor()) {
            executor.execute(() -> {
                entered.countDown();
                try {
                    if (release.await(5, TimeUnit.SECONDS)) finished.countDown();
                } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            });
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var shutdown = closer.submit(executor::shutdown);
            await().atMost(Duration.ofSeconds(2)).until(() -> executor.getThreadPoolExecutor().isShutdown());
            assertThat(shutdown.isDone()).isFalse();
            release.countDown();
            shutdown.get(2, TimeUnit.SECONDS);
            assertThat(finished.getCount()).isZero();
        } finally { release.countDown(); executor.shutdown(); }
    }
}
