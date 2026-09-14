package com.guildup.discord.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;

/** 같은 Guild/User 이벤트는 도착 순서대로, 서로 다른 사용자는 제한된 풀에서 병렬 실행한다. */
@Component
public class DiscordMemberEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(DiscordMemberEventDispatcher.class);

    private final TaskExecutor executor;
    private final SerialQueue[] queues = new SerialQueue[256];

    public DiscordMemberEventDispatcher(
            @Qualifier("discordMemberEventExecutor") TaskExecutor executor
    ) {
        this.executor = executor;
        for (int index = 0; index < queues.length; index++) {
            queues[index] = new SerialQueue(index);
        }
    }

    public void dispatch(String discordGuildId, String discordUserId, Runnable task) {
        String key = discordGuildId + ':' + discordUserId;
        queues[Math.floorMod(key.hashCode(), queues.length)].add(task);
    }

    private final class SerialQueue {
        private final int stripe;
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private boolean running;

        private SerialQueue(int stripe) {
            this.stripe = stripe;
        }

        private synchronized void add(Runnable task) {
            tasks.addLast(task);
            if (!running) {
                running = true;
                scheduleNext();
            }
        }

        private void scheduleNext() {
            try {
                executor.execute(this::runNext);
            } catch (RuntimeException exception) {
                synchronized (this) {
                    tasks.clear();
                    running = false;
                }
                log.error("Discord member event dispatch rejected - stripe: {}", stripe, exception);
            }
        }

        private void runNext() {
            Runnable task;
            synchronized (this) {
                task = tasks.peekFirst();
            }
            try {
                if (task != null) task.run();
            } finally {
                boolean hasNext;
                synchronized (this) {
                    tasks.pollFirst();
                    hasNext = !tasks.isEmpty();
                    if (!hasNext) running = false;
                }
                if (hasNext) scheduleNext();
            }
        }
    }
}
