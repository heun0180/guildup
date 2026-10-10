package com.guildup.user.auth.service;

import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** 접속 기록 실패가 기존 인증 흐름을 깨지 않도록 별도 트랜잭션에서 처리한다. */
@Service
public class UserActivityService {
    private static final Logger log = LoggerFactory.getLogger(UserActivityService.class);
    private static final String NEXT_ACTIVITY = UserActivityService.class.getName() + ".nextActivity";
    private static final Duration INTERVAL = Duration.ofMinutes(10);
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public UserActivityService(JdbcTemplate jdbc, Clock clock, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc; this.clock = clock; this.transactions = new TransactionTemplate(transactionManager);
    }

    public void login(Long userId, long version, HttpSession session) {
        Instant now = clock.instant();
        try {
            transactions.executeWithoutResult(ignored -> jdbc.update("""
                    update users set last_login_at = ?,
                        last_active_at = case when last_active_at is null or last_active_at < ? then ? else last_active_at end
                    where id = ? and status = 'ACTIVE' and authentication_version = ?
                        and (last_login_at is null or last_login_at < ?)
                    """, timestamp(now), timestamp(now), timestamp(now), userId, version, timestamp(now)));
            session.setAttribute(NEXT_ACTIVITY, now.plus(INTERVAL));
        } catch (DataAccessException | org.springframework.transaction.TransactionException failure) {
            log.warn("Login timestamp persistence failed. type={}", failure.getClass().getSimpleName());
        } catch (IllegalStateException ignored) { /* Concurrent logout. */ }
    }

    public void activity(Long userId, HttpSession session) {
        Instant now = clock.instant();
        try {
            synchronized (session) {
                if (session.getAttribute(NEXT_ACTIVITY) instanceof Instant next && now.isBefore(next)) return;
                // DB predicate also protects against concurrent requests from other browsers/servers.
                Instant next = transactions.execute(ignored -> {
                    int updated = jdbc.update("""
                        update users set last_active_at = ? where id = ? and status = 'ACTIVE'
                            and (last_active_at is null or last_active_at <= ?)
                        """, timestamp(now), userId, timestamp(now.minus(INTERVAL)));
                    if (updated > 0) return now.plus(INTERVAL);
                    // Align this session to the persisted timestamp. Starting another ten-minute
                    // window after a skipped update could otherwise leave a busy browser stale for twenty minutes.
                    var stored = jdbc.query("select last_active_at from users where id = ? and status = 'ACTIVE'",
                            (rs, row) -> rs.getTimestamp(1), userId);
                    return stored.isEmpty() || stored.getFirst() == null ? now.plus(INTERVAL)
                            : stored.getFirst().toInstant().plus(INTERVAL);
                });
                session.setAttribute(NEXT_ACTIVITY, next);
            }
        } catch (DataAccessException | org.springframework.transaction.TransactionException failure) {
            log.warn("Activity timestamp persistence failed. type={}", failure.getClass().getSimpleName());
            // Avoid hammering a temporarily unavailable DB on every request; retry after a minute.
            try { session.setAttribute(NEXT_ACTIVITY, now.plusSeconds(60)); }
            catch (IllegalStateException ignored) { /* Concurrent logout. */ }
        } catch (IllegalStateException ignored) { /* Concurrent logout. */ }
    }

    private static Timestamp timestamp(Instant at) { return Timestamp.from(at); }
}
