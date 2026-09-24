package com.guildup.bingo.service;

import com.guildup.bingo.dto.BingoAggregationJobResponse;
import com.guildup.bingo.dto.BingoAggregationResponse;
import com.guildup.bingo.repository.BingoEventRepository;
import com.guildup.community.service.CommunityAccessService;
import com.guildup.pubg.service.PubgMatchSyncService;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/** 오래 걸리는 PUBG Match/Telemetry 집계를 HTTP 요청 수명과 분리한다. */
@Service
public class BingoAggregationJobService {
    private static final Logger log = LoggerFactory.getLogger(BingoAggregationJobService.class);

    private final BingoAggregationService aggregation;
    private final CommunityAccessService access;
    private final TaskExecutor executor;
    private final Clock clock;
    private final BingoAggregationRuntimeProbe runtimeProbe;
    private final BingoEventRepository events;
    private final Map<JobKey, StoredJob> jobs = new ConcurrentHashMap<>();

    @Autowired
    public BingoAggregationJobService(BingoAggregationService aggregation,
                                      CommunityAccessService access,
                                      @Qualifier("bingoAggregationExecutor") TaskExecutor executor,
                                      Clock clock,
                                      BingoAggregationRuntimeProbe runtimeProbe,
                                      BingoEventRepository events) {
        this.aggregation = aggregation;
        this.access = access;
        this.executor = executor;
        this.clock = clock;
        this.runtimeProbe = runtimeProbe;
        this.events = events;
    }

    BingoAggregationJobService(BingoAggregationService aggregation, CommunityAccessService access,
            TaskExecutor executor, Clock clock, BingoAggregationRuntimeProbe runtimeProbe) {
        this(aggregation, access, executor, clock, runtimeProbe, null);
    }

    public BingoAggregationJobResponse start(Long userId, Long communityId, Long communityGameId, Long bingoId) {
        access.requireCommunityAdmin(userId, communityId);
        if (events != null && !events.existsByIdAndCommunityIdAndCommunityGameId(bingoId, communityId, communityGameId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "빙고를 찾을 수 없습니다.");
        return start(JobKey.full(bingoId), userId, communityId, communityGameId, bingoId, null);
    }

    public BingoAggregationJobResponse startPersonal(Long userId, Long communityId, Long communityGameId, Long bingoId) {
        if (events != null && !events.existsByIdAndCommunityIdAndCommunityGameId(bingoId, communityId, communityGameId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "빙고를 찾을 수 없습니다.");
        Long participantId = aggregation.resolvePersonalParticipantId(userId, communityId, bingoId);
        JobKey key = JobKey.personal(bingoId, participantId);
        StoredJob current = jobs.get(key);
        if (isRunningFor(current, communityId, communityGameId)) return current.response();
        aggregation.validatePersonalRequest(userId, communityId, bingoId);
        return start(key, userId, communityId, communityGameId, bingoId, participantId);
    }

    private BingoAggregationJobResponse start(JobKey key, Long userId, Long communityId, Long communityGameId,
                                               Long bingoId, Long participantId) {
        StoredJob first = jobs.get(key);
        if (isRunningFor(first, communityId, communityGameId)) return first.response();
        BingoAggregationJobResponse queued;
        synchronized (jobs) {
            StoredJob stored = jobs.get(key);
            if (isRunningFor(stored, communityId, communityGameId)) return stored.response();
            Instant requestedAt = clock.instant();
            queued = new BingoAggregationJobResponse(bingoId, "RUNNING", requestedAt, null,
                    null, null, null, null, "집계를 시작했습니다.");
            put(key, communityId, communityGameId, queued);
        }
        try {
            executor.execute(() -> run(key, userId, communityId, communityGameId, bingoId,
                    participantId, queued.requestedAt()));
        } catch (RuntimeException exception) {
            BingoAggregationJobResponse failed = failed(bingoId, queued.requestedAt(), null,
                    "집계 작업을 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.");
            put(key, communityId, communityGameId, failed);
            throw exception;
        }
        return queued;
    }

    public BingoAggregationJobResponse status(Long userId, Long communityId, Long communityGameId, Long bingoId) {
        access.requireCommunityAdmin(userId, communityId);
        StoredJob stored = jobs.get(JobKey.full(bingoId));
        return stored != null && stored.belongsTo(communityId, communityGameId)
                ? stored.response() : BingoAggregationJobResponse.idle(bingoId);
    }

    public BingoAggregationJobResponse statusPersonal(Long userId, Long communityId, Long communityGameId, Long bingoId) {
        if (events != null && !events.existsByIdAndCommunityIdAndCommunityGameId(bingoId, communityId, communityGameId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "빙고를 찾을 수 없습니다.");
        Long participantId = aggregation.resolvePersonalParticipantId(userId, communityId, bingoId);
        StoredJob stored = jobs.get(JobKey.personal(bingoId, participantId));
        return stored != null && stored.belongsTo(communityId, communityGameId)
                ? stored.response() : BingoAggregationJobResponse.idle(bingoId);
    }

    private void run(JobKey key, Long userId, Long communityId, Long communityGameId, Long bingoId,
                     Long participantId, Instant requestedAt) {
        Instant startedAt = clock.instant();
        ScheduledFuture<?> sampling = runtimeProbe.start(bingoId);
        put(key, communityId, communityGameId, new BingoAggregationJobResponse(bingoId, "RUNNING",
                requestedAt, startedAt, null, null, null, null, "PUBG 경기 기록을 조회하고 있습니다."));
        try {
            PubgMatchSyncService.ProgressListener progress = (stage, completed, total, message) ->
                    put(key, communityId, communityGameId,
                    new BingoAggregationJobResponse(bingoId, "RUNNING", requestedAt, startedAt,
                            null, null, null, null, message, stage, completed, total));
            BingoAggregationResponse result = participantId == null
                    ? aggregation.aggregate(userId, communityId, bingoId, progress)
                    : aggregation.aggregatePersonal(userId, communityId, bingoId, progress);
            put(key, communityId, communityGameId, new BingoAggregationJobResponse(bingoId, "SUCCEEDED", requestedAt,
                    startedAt, clock.instant(), result.processedMatches(), result.updatedParticipants(),
                    result.aggregatedAt(), "빙고 집계가 완료되었습니다.", "COMPLETED", null, null));
        } catch (RuntimeException exception) {
            String message = userMessage(exception);
            put(key, communityId, communityGameId, failed(bingoId, requestedAt, startedAt, message));
            log.error("Bingo aggregation job failed - scope={}, bingoId={}, participantId={}, communityId={}",
                    key.scope(), bingoId, participantId, communityId, exception);
        } finally {
            runtimeProbe.stop(bingoId, sampling);
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

    private boolean isRunningFor(StoredJob job, Long communityId, Long communityGameId) {
        return job != null && job.belongsTo(communityId, communityGameId)
                && "RUNNING".equals(job.response().state());
    }

    private void put(JobKey key, Long communityId, Long communityGameId, BingoAggregationJobResponse response) {
        jobs.put(key, new StoredJob(communityId, communityGameId, response));
    }

    private enum JobScope { FULL, PERSONAL }
    private record JobKey(JobScope scope, Long eventId, Long participantId) {
        static JobKey full(Long eventId) { return new JobKey(JobScope.FULL, eventId, null); }
        static JobKey personal(Long eventId, Long participantId) {
            return new JobKey(JobScope.PERSONAL, eventId, participantId);
        }
    }

    private record StoredJob(Long communityId, Long communityGameId, BingoAggregationJobResponse response) {
        boolean belongsTo(Long expectedCommunityId, Long expectedCommunityGameId) {
            return communityId.equals(expectedCommunityId) && communityGameId.equals(expectedCommunityGameId);
        }
    }
}
