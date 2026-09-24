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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BingoAggregationJobServiceTests {
    @Mock BingoAggregationService aggregation;
    @Mock CommunityAccessService access;
    Queue<Runnable> tasks;
    BingoAggregationJobService jobs;

    @BeforeEach
    void setUp() {
        tasks = new ArrayDeque<>();
        TaskExecutor executor = tasks::add;
        jobs = new BingoAggregationJobService(aggregation, access, executor,
                Clock.fixed(Instant.parse("2026-09-24T05:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void requestReturnsImmediatelyAndTheWorkerPublishesTheSuccessfulResult() {
        when(aggregation.aggregate(1L, 10L, 100L)).thenReturn(new BingoAggregationResponse(
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
        when(aggregation.aggregate(1L, 10L, 100L)).thenThrow(
                new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "30분 후 다시 실행해 주세요."));

        jobs.start(1L, 10L, 20L, 100L);
        tasks.remove().run();

        var failed = jobs.status(1L, 10L, 20L, 100L);
        assertThat(failed.state()).isEqualTo("FAILED");
        assertThat(failed.message()).isEqualTo("30분 후 다시 실행해 주세요.");
        assertThat(failed.aggregatedAt()).isNull();
    }
}
