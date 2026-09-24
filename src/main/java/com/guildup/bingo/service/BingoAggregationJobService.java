package com.guildup.bingo.service;

import com.guildup.bingo.dto.BingoAggregationJobResponse;
import com.guildup.bingo.dto.BingoAggregationResponse;
import com.guildup.community.service.CommunityAccessService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 오래 걸리는 PUBG Match/Telemetry 집계를 HTTP 요청 수명과 분리한다. */
@Service
public class BingoAggregationJobService {
    private static final Logger log = LoggerFactory.getLogger(BingoAggregationJobService.class);

    private final BingoAggregationService aggregation;
    private final CommunityAccessService access;
    private final TaskExecutor executor;
    private final Clock clock;
    private final Map<Long, StoredJob> jobs = new ConcurrentHashMap<>();

    public BingoAggregationJobService(BingoAggregationService aggregation,
                                      CommunityAccessService access,
                                      @Qualifier("bingoAggregationExecutor") TaskExecutor executor,
                                      Clock clock) {
        this.aggregation = aggregation;
        this.access = access;
        this.executor = executor;
        this.clock = clock;
    }

    public BingoAggregationJobResponse start(Long userId, Long communityId, Long communityGameId, Long bingoId) {
        access.requireCommunityAdmin(userId, communityId);
        BingoAggregationJobResponse queued;
        synchronized (jobs) {
            StoredJob stored = jobs.get(bingoId);
            BingoAggregationJobResponse current = stored == null ? null : stored.response();
            if (stored != null && stored.belongsTo(communityId, communityGameId)
                    && "RUNNING".equals(current.state())) return current;
            Instant requestedAt = clock.instant();
            queued = new BingoAggregationJobResponse(bingoId, "RUNNING", requestedAt, null,
                    null, null, null, null, "집계를 시작했습니다.");
            put(communityId, communityGameId, queued);
        }
        try {
            executor.execute(() -> run(userId, communityId, communityGameId, bingoId, queued.requestedAt()));
        } catch (RuntimeException exception) {
            BingoAggregationJobResponse failed = failed(bingoId, queued.requestedAt(), null,
                    "집계 작업을 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.");
            put(communityId, communityGameId, failed);
            throw exception;
        }
        return queued;
    }

    public BingoAggregationJobResponse status(Long userId, Long communityId, Long communityGameId, Long bingoId) {
        access.requireCommunityAdmin(userId, communityId);
        StoredJob stored = jobs.get(bingoId);
        return stored != null && stored.belongsTo(communityId, communityGameId)
                ? stored.response() : BingoAggregationJobResponse.idle(bingoId);
    }

    private void run(Long userId, Long communityId, Long communityGameId, Long bingoId, Instant requestedAt) {
        Instant startedAt = clock.instant();
        put(communityId, communityGameId, new BingoAggregationJobResponse(bingoId, "RUNNING",
                requestedAt, startedAt, null, null, null, null, "PUBG 경기 기록을 조회하고 있습니다."));
        try {
            BingoAggregationResponse result = aggregation.aggregate(userId, communityId, bingoId);
            put(communityId, communityGameId, new BingoAggregationJobResponse(bingoId, "SUCCEEDED", requestedAt,
                    startedAt, clock.instant(), result.processedMatches(), result.updatedParticipants(),
                    result.aggregatedAt(), "빙고 집계가 완료되었습니다."));
        } catch (RuntimeException exception) {
            String message = userMessage(exception);
            put(communityId, communityGameId, failed(bingoId, requestedAt, startedAt, message));
            log.error("Bingo aggregation job failed - bingoId={}, communityId={}",
                    bingoId, communityId, exception);
        }
    }

    private BingoAggregationJobResponse failed(Long bingoId, Instant requestedAt,
                                                Instant startedAt, String message) {
        return new BingoAggregationJobResponse(bingoId, "FAILED", requestedAt, startedAt,
                clock.instant(), null, null, null, message);
    }

    private String userMessage(RuntimeException exception) {
        if (exception instanceof ResponseStatusException response && response.getReason() != null)
            return response.getReason();
        return "빙고 집계 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.";
    }

    private void put(Long communityId, Long communityGameId, BingoAggregationJobResponse response) {
        jobs.put(response.bingoId(), new StoredJob(communityId, communityGameId, response));
    }

    private record StoredJob(Long communityId, Long communityGameId, BingoAggregationJobResponse response) {
        boolean belongsTo(Long expectedCommunityId, Long expectedCommunityGameId) {
            return communityId.equals(expectedCommunityId) && communityGameId.equals(expectedCommunityGameId);
        }
    }
}
