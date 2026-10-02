package com.guildup.community.service;

import com.guildup.monitoring.domain.MonitoringCategory;
import com.guildup.monitoring.domain.MonitoringEventCode;
import com.guildup.monitoring.service.MonitoringEventService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommunityOperationLoggingTests {
    @Test
    void expectedDuplicateDoesNotBecomeDatabaseIncident() {
        Logger logger = mock(Logger.class);
        var monitoring = mock(MonitoringEventService.class);
        var failure = new DataIntegrityViolationException("duplicate", new SQLException("duplicate", "23505"));

        CommunityOperationLogging.integrityFailure(logger, monitoring, "join", 1L, 2L, failure);

        verifyNoInteractions(monitoring);
        verify(logger).warn(anyString(), eq("join"), eq(1L), eq(2L), eq("23505"));
    }

    @Test
    void unexpectedConstraintFailureIsVisibleDespiteExistingConflictResponse() {
        Logger logger = mock(Logger.class);
        var monitoring = mock(MonitoringEventService.class);
        var failure = new DataIntegrityViolationException("constraint", new SQLException("foreign key", "23503"));

        CommunityOperationLogging.integrityFailure(logger, monitoring, "join", 1L, 2L, failure);

        verify(monitoring).recordError(eq(MonitoringCategory.DATABASE), eq(MonitoringEventCode.DATABASE_ERROR),
                anyString(), eq(1L), eq(2L), eq("join"), argThat(metadata -> "23503".equals(metadata.get("sqlState"))));
    }

    @Test
    void successLifecycleLogIsDeferredUntilCommit() {
        AtomicBoolean emitted = new AtomicBoolean();
        TransactionSynchronizationManager.initSynchronization();
        try {
            CommunityOperationLogging.afterCommit(() -> emitted.set(true));
            assertThat(emitted).isFalse();
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
            assertThat(emitted).isTrue();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
