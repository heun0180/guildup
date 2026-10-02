package com.guildup.community.service;

import com.guildup.monitoring.domain.MonitoringCategory;
import com.guildup.monitoring.domain.MonitoringEventCode;
import com.guildup.monitoring.service.MonitoringEventService;
import org.slf4j.Logger;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;

/** 진단만 추가하며 기존 중복 요청 응답과 트랜잭션 결과를 유지한다. */
final class CommunityOperationLogging {
    private CommunityOperationLogging() {}

    static void afterCommit(Runnable log) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { log.run(); }
            });
        } else {
            log.run();
        }
    }

    static void integrityFailure(Logger log, MonitoringEventService monitoring, String operation,
                                 Long communityId, Long userId, DataIntegrityViolationException failure) {
        String sqlState = null;
        Throwable root = failure;
        for (int depth = 0; root != null && depth < 16; depth++) {
            if (root instanceof SQLException sql) sqlState = sql.getSQLState();
            if (root.getCause() == null || root.getCause() == root) break;
            root = root.getCause();
        }
        String detail = root == null || root.getMessage() == null ? "" : root.getMessage().toLowerCase(Locale.ROOT);
        boolean duplicate = "23505".equals(sqlState)
                || (sqlState == null && (detail.contains("duplicate") || detail.contains("unique")));
        if (duplicate) {
            log.warn("Concurrent duplicate request rejected. operation={}, communityId={}, userId={}, sqlState={}",
                    operation, communityId, userId, sqlState);
            return;
        }
        log.error("Unexpected database integrity failure translated by existing conflict handler. operation={}, communityId={}, userId={}, sqlState={}",
                operation, communityId, userId, sqlState, failure);
        if (monitoring != null) {
            var metadata = new LinkedHashMap<String, Object>();
            metadata.put("operation", operation);
            metadata.put("exceptionClass", failure.getClass().getSimpleName());
            if (sqlState != null) metadata.put("sqlState", sqlState);
            monitoring.recordError(MonitoringCategory.DATABASE, MonitoringEventCode.DATABASE_ERROR,
                    "Unexpected database integrity failure", communityId, userId, operation, metadata);
        }
    }
}
