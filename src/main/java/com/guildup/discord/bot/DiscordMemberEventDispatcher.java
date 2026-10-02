package com.guildup.discord.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import com.guildup.monitoring.logging.LogContext;
import com.guildup.monitoring.logging.FailureLogContext;
import com.guildup.monitoring.domain.MonitoringCategory;
import com.guildup.monitoring.domain.MonitoringEventCode;
import com.guildup.monitoring.service.MonitoringEventService;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayDeque;

/** 같은 Guild/User 이벤트는 도착 순서대로, 서로 다른 사용자는 제한된 풀에서 병렬 실행한다. */
@Component
public class DiscordMemberEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(DiscordMemberEventDispatcher.class);

    private final TaskExecutor executor;
    private final SerialQueue[] queues = new SerialQueue[256];
    private MonitoringEventService monitoring;

    @Autowired
    void configureMonitoring(MonitoringEventService monitoring) { this.monitoring = monitoring; }

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
        try (var ignored = LogContext.scope(java.util.Map.of("discordGuildId", discordGuildId,
                "discordUserId", discordUserId, "jobName", "discordMemberEvent"))) {
            queues[Math.floorMod(key.hashCode(), queues.length)].add(new PendingEvent(
                    discordGuildId, discordUserId, LogContext.wrap(() -> {
                        try {
                            task.run();
                        } catch (RuntimeException exception) {
                            if (!FailureLogContext.isLogged(exception)) {
                                log.error("Discord member event task failed. jobName=discordMemberEvent, discordGuildId={}, discordUserId={}",
                                        discordGuildId, discordUserId, exception);
                                recordFailure("EXECUTE", discordGuildId, discordUserId, exception);
                            }
                        }
                    })));
        }
    }

    private record PendingEvent(String guildId, String userId, Runnable task) {}

    private void recordFailure(String stage, String guildId, String userId, RuntimeException exception) {
        if (monitoring == null) return;
        var metadata = new java.util.LinkedHashMap<String, Object>();
        metadata.put("jobName", "discordMemberEvent");
        metadata.put("stage", stage);
        if (guildId != null) metadata.put("discordGuildId", guildId);
        if (userId != null) metadata.put("discordUserId", userId);
        metadata.put("exceptionClass", exception.getClass().getSimpleName());
        monitoring.recordError(MonitoringCategory.DISCORD, MonitoringEventCode.DISCORD_MEMBER_LOOKUP_FAILED,
                "Discord member event dispatch failed", null, null, "discordMemberEvent", metadata);
    }

    private final class SerialQueue {
        private final int stripe;
        private final ArrayDeque<PendingEvent> tasks = new ArrayDeque<>();
        private boolean running;

        private SerialQueue(int stripe) {
            this.stripe = stripe;
        }

        private synchronized void add(PendingEvent task) {
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
                PendingEvent rejected;
                int discarded;
                synchronized (this) {
                    rejected = tasks.peekFirst();
                    discarded = tasks.size();
                    tasks.clear();
                    running = false;
                }
                String guildId = rejected == null ? null : rejected.guildId();
                String userId = rejected == null ? null : rejected.userId();
                log.error("Discord member event dispatch rejected. stripe={}, discordGuildId={}, discordUserId={}, discardedEvents={}",
                        stripe, guildId, userId, discarded, exception);
                recordFailure("ENQUEUE", guildId, userId, exception);
            }
        }

        private void runNext() {
            PendingEvent task;
            synchronized (this) {
                task = tasks.peekFirst();
            }
            try {
                if (task != null) task.task().run();
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
