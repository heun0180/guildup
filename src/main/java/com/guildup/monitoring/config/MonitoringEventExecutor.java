package com.guildup.monitoring.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.*;

/** One database writer with a hard backlog limit and a bounded deployment drain. */
public final class MonitoringEventExecutor extends ThreadPoolExecutor {
    private static final Logger log = LoggerFactory.getLogger(MonitoringEventExecutor.class);

    public MonitoringEventExecutor() {
        super(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(256), runnable -> {
            Thread thread = new Thread(runnable, "monitoring-event-writer");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    public void stopWriter() {
        shutdown();
        try {
            if (awaitTermination(5, TimeUnit.SECONDS)) return;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        int dropped = shutdownNow().size();
        log.warn("Monitoring writer shutdown drain timed out - jobName=monitoringEventWriter droppedEvents={}", dropped);
    }
}
