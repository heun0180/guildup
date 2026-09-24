package com.guildup.bingo.service;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** 집계 전/중/후의 pool, thread, CPU, heap, GC 상태를 로그로 남기는 관찰 전용 probe다. */
@Component
public class BingoAggregationRuntimeProbe {
    private static final Logger log = LoggerFactory.getLogger(BingoAggregationRuntimeProbe.class);
    private final HikariDataSource dataSource;
    private final ThreadPoolTaskExecutor executor;
    private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "bingo-baseline-probe");
        thread.setDaemon(true);
        return thread;
    });

    public BingoAggregationRuntimeProbe(HikariDataSource dataSource,
            @Qualifier("bingoAggregationExecutor") ThreadPoolTaskExecutor executor) {
        this.dataSource = dataSource;
        this.executor = executor;
    }

    public ScheduledFuture<?> start(Long bingoId) {
        sample("BEFORE", bingoId);
        return sampler.scheduleAtFixedRate(() -> sample("RUNNING", bingoId), 0, 1, TimeUnit.SECONDS);
    }

    public void stop(Long bingoId, ScheduledFuture<?> sampling) {
        if (sampling != null) sampling.cancel(false);
        sample("AFTER", bingoId);
    }

    private void sample(String phase, Long bingoId) {
        HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
        int active = pool == null ? -1 : pool.getActiveConnections();
        int idle = pool == null ? -1 : pool.getIdleConnections();
        int pending = pool == null ? -1 : pool.getThreadsAwaitingConnection();
        int total = pool == null ? -1 : pool.getTotalConnections();
        log.info("[BINGO_POOL] phase={} bingoId={} active={} idle={} pending={} total={} maximumPoolSize={}",
                phase, bingoId, active, idle, pending, total, dataSource.getMaximumPoolSize());

        long[] tomcat = tomcatThreads();
        log.info("[BINGO_THREADS] phase={} bingoId={} tomcatCurrentThreadsBusyApprox={} tomcatCurrentThreadCount={} "
                        + "executorActive={} executorPoolSize={} executorQueueSize={}",
                phase, bingoId, tomcat[0], tomcat[1], executor.getActiveCount(), executor.getPoolSize(),
                executor.getThreadPoolExecutor().getQueue().size());

        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        double processCpu = os instanceof com.sun.management.OperatingSystemMXBean extended
                ? extended.getProcessCpuLoad() : -1;
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memory.getHeapMemoryUsage();
        List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
        long gcCount = collectors.stream().mapToLong(value -> Math.max(0, value.getCollectionCount())).sum();
        long gcTimeMs = collectors.stream().mapToLong(value -> Math.max(0, value.getCollectionTime())).sum();
        log.info("[BINGO_RESOURCE] phase={} bingoId={} processCpu={} heapUsed={} heapMax={} gcCount={} gcTimeMs={}",
                phase, bingoId, processCpu, heap.getUsed(), heap.getMax(), gcCount, gcTimeMs);
    }

    private long[] tomcatThreads() {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = server.queryNames(new ObjectName("Tomcat:type=ThreadPool,name=*"), null)
                    .stream().findFirst().orElse(null);
            if (name == null) return tomcatThreadsFromStacks();
            return new long[] {
                    ((Number) server.getAttribute(name, "currentThreadsBusy")).longValue(),
                    ((Number) server.getAttribute(name, "currentThreadCount")).longValue()
            };
        } catch (Exception ignored) {
            return tomcatThreadsFromStacks();
        }
    }

    private long[] tomcatThreadsFromStacks() {
        Map<Thread, StackTraceElement[]> allThreads = Thread.getAllStackTraces();
        long total = allThreads.keySet().stream()
                .filter(thread -> thread.getName().startsWith("http-nio-"))
                .count();
        long busy = allThreads.entrySet().stream()
                .filter(entry -> entry.getKey().getName().startsWith("http-nio-"))
                .filter(entry -> java.util.Arrays.stream(entry.getValue()).anyMatch(frame ->
                        frame.getClassName().startsWith("org.apache.catalina.core.StandardWrapperValve")
                                || frame.getClassName().startsWith("org.springframework.web.servlet.DispatcherServlet")))
                .count();
        return new long[] {busy, total};
    }
}
