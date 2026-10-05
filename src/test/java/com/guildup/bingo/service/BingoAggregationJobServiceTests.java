package com.guildup.bingo.service;

import com.guildup.bingo.dto.BingoAggregationResponse;
import com.guildup.community.service.CommunityAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BingoAggregationJobServiceTests {
    @Mock BingoAggregationService aggregation;
    @Mock CommunityAccessService access;
    @Mock BingoAggregationRuntimeProbe runtimeProbe;
    Queue<Runnable> tasks;
    BingoAggregationJobService jobs;

    @BeforeEach
    void setUp() {
        tasks = new ArrayDeque<>();
        TaskExecutor executor = tasks::add;
        jobs = new BingoAggregationJobService(aggregation, access, executor,
                Clock.fixed(Instant.parse("2026-09-24T05:00:00Z"), ZoneOffset.UTC), runtimeProbe);
    }

    @Test
    void requestReturnsImmediatelyAndTheWorkerPublishesTheSuccessfulResult() {
        when(aggregation.aggregate(eq(1L), eq(10L), eq(100L), any())).thenReturn(new BingoAggregationResponse(
                100L, 12, 3, "ACTIVE", Instant.parse("2026-09-24T05:00:00Z")));

        var started = jobs.start(1L, 10L, 20L, 100L);

        assertThat(started.state()).isEqualTo("RUNNING");
        assertThat(tasks).hasSize(1);
        verifyNoInteractions(aggregation);

        tasks.remove().run();

        var completed = jobs.status(1L, 10L, 20L, 100L);
        assertThat(completed.state()).isEqualTo("SUCCEEDED");
        assertThat(completed.processedMatches()).isEqualTo(12);
        assertThat(completed.updatedParticipants()).isEqualTo(3);
        assertThat(completed.aggregatedAt()).isEqualTo(Instant.parse("2026-09-24T05:00:00Z"));
    }

    @Test
    void partialTelemetryFailurePublishesCompletedWithWarningsAndFailureCount() {
        when(aggregation.aggregate(eq(1L), eq(10L), eq(100L), any())).thenReturn(new BingoAggregationResponse(
                100L, 9, 3, "ACTIVE", Instant.parse("2026-09-24T05:00:00Z"), 1));

        jobs.start(1L, 10L, 20L, 100L);
        tasks.remove().run();

        var completed = jobs.status(1L, 10L, 20L, 100L);
        assertThat(completed.state()).isEqualTo("COMPLETED_WITH_WARNINGS");
        assertThat(completed.telemetryFailures()).isEqualTo(1);
        assertThat(completed.message()).contains("다음 집계에서 다시 시도");
        assertThat(completed.processedMatches()).isEqualTo(9);
    }

    @Test
    void matchLookupFailureWithoutTelemetryFailurePublishesRetryDetails() {
        var failure = new com.guildup.pubg.service.PubgMatchSyncService.CollectionFailure(
                com.guildup.pubg.service.PubgMatchSyncService.FailureStage.MATCH_LOOKUP, "missing-match");
        when(aggregation.aggregate(eq(1L), eq(10L), eq(100L), any())).thenReturn(new BingoAggregationResponse(
                100L, 9, 3, "SETTLING", Instant.parse("2026-09-24T05:00:00Z"))
                .withCollectionFailures(0, java.util.List.of(failure)));
        jobs.start(1L, 10L, 20L, 100L);
        tasks.remove().run();
        var result = jobs.status(1L, 10L, 20L, 100L);
        assertThat(result.state()).isEqualTo("COMPLETED_WITH_WARNINGS");
        assertThat(result.telemetryFailures()).isZero();
        assertThat(result.message()).contains("최종 확정을 보류", "Match 조회 실패", "missing-match", "다음 집계에서 다시 시도");
    }

    @Test
    void aSecondClickWhileRunningReturnsTheSameJobWithoutEnqueueingAnotherOne() {
        var first = jobs.start(1L, 10L, 20L, 100L);
        var second = jobs.start(1L, 10L, 20L, 100L);

        assertThat(second).isEqualTo(first);
        assertThat(tasks).hasSize(1);
    }

    @Test
    void jobStatusIsScopedToTheCommunityAndCommunityGame() {
        jobs.start(1L, 10L, 20L, 100L);

        assertThat(jobs.status(1L, 10L, 21L, 100L).state()).isEqualTo("IDLE");
        assertThat(jobs.status(1L, 11L, 20L, 100L).state()).isEqualTo("IDLE");
        assertThat(jobs.status(1L, 10L, 20L, 100L).state()).isEqualTo("RUNNING");
    }

    @Test
    void aFailedWorkerKeepsAVisibleFailureInsteadOfLeavingTheButtonRunningForever() {
        when(aggregation.aggregate(eq(1L), eq(10L), eq(100L), any())).thenThrow(
                new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "30분 후 다시 실행해 주세요."));

        jobs.start(1L, 10L, 20L, 100L);
        tasks.remove().run();

        var failed = jobs.status(1L, 10L, 20L, 100L);
        assertThat(failed.state()).isEqualTo("FAILED");
        assertThat(failed.message()).isEqualTo("30분 후 다시 실행해 주세요.");
        assertThat(failed.aggregatedAt()).isNull();
    }

    @Test
    void duplicatePersonalClicksShareOneWorkerButDifferentParticipantsRunIndependently() {
        when(aggregation.validatePersonalRequest(1L, 10L, 100L)).thenReturn(101L);
        when(aggregation.validatePersonalRequest(2L, 10L, 100L)).thenReturn(102L);
        when(aggregation.resolvePersonalParticipantId(1L, 10L, 100L)).thenReturn(101L);
        when(aggregation.resolvePersonalParticipantId(2L, 10L, 100L)).thenReturn(102L);

        var first = jobs.startPersonal(1L, 10L, 20L, 100L);
        var duplicate = jobs.startPersonal(1L, 10L, 20L, 100L);
        var other = jobs.startPersonal(2L, 10L, 20L, 100L);

        assertThat(duplicate).isEqualTo(first);
        assertThat(other.state()).isEqualTo("RUNNING");
        assertThat(tasks).hasSize(2);
    }

    @Test
    void personalAndFullJobsForTheSameEventHaveDifferentKeys() {
        when(aggregation.validatePersonalRequest(1L, 10L, 100L)).thenReturn(101L);
        when(aggregation.resolvePersonalParticipantId(1L, 10L, 100L)).thenReturn(101L);

        jobs.startPersonal(1L, 10L, 20L, 100L);
        jobs.start(1L, 10L, 20L, 100L);

        assertThat(tasks).hasSize(2);
    }

    @Test
    void ordinaryMemberCannotStartFullAggregation() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Community management access denied"))
                .when(access).requireCommunityAdmin(1L, 10L);

        assertThatThrownBy(() -> jobs.start(1L, 10L, 20L, 100L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(tasks).isEmpty();
    }

    @Test
    void resourceMonitoringFailuresCannotStrandOrFailAnOtherwiseSuccessfulJob() {
        when(runtimeProbe.start(100L)).thenThrow(new IllegalStateException("probe start unavailable"));
        doThrow(new IllegalStateException("probe stop unavailable")).when(runtimeProbe).stop(100L, null);
        when(aggregation.aggregate(eq(1L), eq(10L), eq(100L), any())).thenReturn(new BingoAggregationResponse(
                100L, 12, 3, "ACTIVE", Instant.parse("2026-09-24T05:00:00Z")));

        jobs.start(1L, 10L, 20L, 100L);
        tasks.remove().run();

        assertThat(jobs.status(1L, 10L, 20L, 100L).state()).isEqualTo("SUCCEEDED");
        verify(aggregation).aggregate(eq(1L), eq(10L), eq(100L), any());
    }

    @Test
    void workerRetainsRequestContextAndLogsFailedStageWithTheOriginalStack() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(BingoAggregationJobService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        RuntimeException failure = new IllegalStateException("calculation failed");
        when(aggregation.aggregate(eq(1L), eq(10L), eq(100L), any())).thenAnswer(invocation -> {
            assertThat(org.slf4j.MDC.get("requestId")).isEqualTo("req-bingo-test");
            assertThat(org.slf4j.MDC.get("communityId")).isEqualTo("10");
            com.guildup.pubg.service.PubgMatchSyncService.ProgressListener progress = invocation.getArgument(3);
            progress.stage("CALCULATE_BINGO", 0, 1, "계산 중");
            throw failure;
        });
        try {
            org.slf4j.MDC.put("requestId", "req-bingo-test");
            jobs.start(1L, 10L, 20L, 100L);
            org.slf4j.MDC.clear();
            tasks.remove().run();

            assertThat(jobs.status(1L, 10L, 20L, 100L).state()).isEqualTo("FAILED");
            assertThat(appender.list).filteredOn(event -> event.getLevel() == ch.qos.logback.classic.Level.ERROR)
                    .singleElement().satisfies(event -> {
                        assertThat(event.getFormattedMessage()).contains("stage=CALCULATE_BINGO", "bingoEventId=100");
                        assertThat(event.getThrowableProxy()).isNotNull();
                    });
            assertThat(org.slf4j.MDC.getCopyOfContextMap()).isNullOrEmpty();
        } finally { org.slf4j.MDC.clear(); logger.detachAppender(appender); appender.stop(); }
    }

}
