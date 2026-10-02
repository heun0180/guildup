package com.guildup.killcompetition.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KillCompetitionMonitoringTests {
    @Test
    void failedPublicationDoesNotHideOrPreventOtherDueCompetitions() {
        var settlements = mock(KillCompetitionSettlementService.class);
        when(settlements.dueCompetitionIds()).thenReturn(List.of(1L, 2L));
        doThrow(new DataAccessResourceFailureException("connection unavailable"))
                .when(settlements).publishDueResult(1L);
        var scheduler = new KillCompetitionResultScheduler(settlements);
        Logger logger = (Logger) LoggerFactory.getLogger(KillCompetitionResultScheduler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            assertThatCode(scheduler::publishDueResults).doesNotThrowAnyException();
            verify(settlements).publishDueResult(2L);
            assertThat(appender.list).filteredOn(event -> event.getLevel() == Level.ERROR)
                    .singleElement().satisfies(event -> {
                        assertThat(event.getFormattedMessage()).contains("killCompetitionId=1", "stage=PUBLISH_RESULT");
                        assertThat(event.getThrowableProxy()).isNotNull();
                    });
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test
    void interimCleanupFailurePreservesOriginalAggregationCause() {
        var store = mock(KillCompetitionSettlementStore.class);
        var aggregator = mock(KillCompetitionPubgAggregator.class);
        var competitions = mock(KillCompetitionService.class);
        var work = new KillCompetitionSettlementStore.SettlementWork(3L, 2L, "steam", Instant.EPOCH,
                Instant.EPOCH.plusSeconds(60), Instant.EPOCH, null, List.of());
        when(store.claimInterim(1L, 2L, 3L)).thenReturn(work);
        RuntimeException original = new IllegalStateException("PUBG aggregation failed");
        RuntimeException cleanup = new DataAccessResourceFailureException("cleanup database failed");
        when(aggregator.aggregate(work.shard(), work.startedAt(), work.rangeEnd(), work.players())).thenThrow(original);
        doThrow(cleanup).when(store).releaseInterim(2L, work);
        var service = new KillCompetitionSettlementService(store, aggregator, competitions);

        assertThatThrownBy(() -> service.calculateInterim(1L, 2L, 3L)).isSameAs(original)
                .satisfies(failure -> assertThat(failure.getSuppressed()).containsExactly(cleanup));
        verifyNoInteractions(competitions);
    }

    @Test
    void claimFailureGetsAContextualStackAndCannotEscapeTheSchedulerBoundary() {
        var store = mock(KillCompetitionSettlementStore.class);
        when(store.claimDueFinal(17L)).thenThrow(new DataAccessResourceFailureException("claim database failed"));
        var aggregator = mock(KillCompetitionPubgAggregator.class);
        var service = new KillCompetitionSettlementService(store, aggregator, mock(KillCompetitionService.class));
        Logger logger = (Logger) LoggerFactory.getLogger(KillCompetitionSettlementService.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            assertThatCode(() -> service.publishDueResult(17L)).doesNotThrowAnyException();
            assertThat(appender.list).filteredOn(event -> event.getLevel() == Level.ERROR)
                    .singleElement().satisfies(event -> {
                        assertThat(event.getFormattedMessage()).contains("killCompetitionId=17", "stage=FINAL_CLAIM");
                        assertThat(event.getThrowableProxy()).isNotNull();
                    });
            verifyNoInteractions(aggregator);
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
}
