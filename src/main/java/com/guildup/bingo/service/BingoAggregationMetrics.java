package com.guildup.bingo.service;

import java.util.concurrent.atomic.AtomicLong;

/** 로컬 baseline 재현 중 집계 호출량과 단계 시간을 한 worker thread 안에서만 수집한다. */
public final class BingoAggregationMetrics {
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private BingoAggregationMetrics() { }

    public static Context begin(Long bingoId) {
        Context context = new Context(bingoId, System.nanoTime());
        CURRENT.set(context);
        return context;
    }

    public static Context current() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }
    public static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    public static final class Context {
        public final Long bingoId;
        public final long aggregationStartedNanos;
        public int playerCount;
        public int playerApiCalls;
        public int uniqueMatchIds;
        public int matchRequested;
        public int matchActualCalls;
        public int matchCacheHits;
        public int matchCacheMisses;
        public int eligibleMatches;
        public int telemetryRequested;
        public int telemetryActualCalls;
        public int telemetryCacheHits;
        public int telemetryCacheMisses;
        public final AtomicLong playerElapsedNanos = new AtomicLong();
        public final AtomicLong matchElapsedNanos = new AtomicLong();
        public final AtomicLong telemetryElapsedNanos = new AtomicLong();
        public final AtomicLong missionElapsedNanos = new AtomicLong();

        private Context(Long bingoId, long aggregationStartedNanos) {
            this.bingoId = bingoId;
            this.aggregationStartedNanos = aggregationStartedNanos;
        }

        public long millis(AtomicLong nanos) { return nanos.get() / 1_000_000; }
    }
}
