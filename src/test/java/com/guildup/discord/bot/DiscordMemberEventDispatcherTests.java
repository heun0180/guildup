package com.guildup.discord.bot;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class DiscordMemberEventDispatcherTests {

    @Test
    void rapidEventsForSameGuildUserKeepArrivalOrder() throws Exception {
        ThreadPoolTaskExecutor executor = executor(4);
        DiscordMemberEventDispatcher dispatcher = new DiscordMemberEventDispatcher(executor);
        List<Integer> order = new CopyOnWriteArrayList<>();
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(3);

        try {
            dispatcher.dispatch("guild", "user", () -> {
                await(releaseFirst);
                order.add(1);
                completed.countDown();
            });
            dispatcher.dispatch("guild", "user", () -> {
                order.add(2);
                completed.countDown();
            });
            dispatcher.dispatch("guild", "user", () -> {
                order.add(3);
                completed.countDown();
            });
            releaseFirst.countDown();

            assertThat(completed.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(order).containsExactly(1, 2, 3);
        } finally {
            releaseFirst.countDown();
            executor.shutdown();
        }
    }

    @Test
    void differentUsersCanRunInParallel() throws Exception {
        ThreadPoolTaskExecutor executor = executor(2);
        DiscordMemberEventDispatcher dispatcher = new DiscordMemberEventDispatcher(executor);
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        try {
            dispatcher.dispatch("guild", "user-a", () -> {
                bothStarted.countDown();
                await(release);
            });
            dispatcher.dispatch("guild", "user-b", () -> {
                bothStarted.countDown();
                await(release);
            });

            assertThat(bothStarted.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    private ThreadPoolTaskExecutor executor(int concurrency) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(20);
        executor.initialize();
        return executor;
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
