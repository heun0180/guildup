package com.guildup.monitoring;

import com.guildup.developer.config.DeveloperAccessService;
import com.guildup.monitoring.logging.RealtimeLogBuffer;
import com.guildup.monitoring.service.RealtimeLogStreamService;
import com.guildup.user.auth.service.CurrentUserSession;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RealtimeLogStreamServiceTests {
    private com.guildup.user.auth.service.AuthSessionService sessions() {
        var sessions = mock(com.guildup.user.auth.service.AuthSessionService.class);
        when(sessions.isSessionCurrent(any(), anyLong())).thenReturn(true);
        return sessions;
    }
    @Test void sessionInvalidatedBetweenInterceptorAndOpenRemainsUnauthorized() {
        var service = new RealtimeLogStreamService(new RealtimeLogBuffer(1000), mock(DeveloperAccessService.class), sessions());
        var session = new MockHttpSession(); session.invalidate();
        try {
            assertThatThrownBy(() -> service.open(session, null, () -> false))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            failure -> assertThat(failure.getStatusCode().value()).isEqualTo(401));
            assertThat(service.connectionCount()).isZero();
        } finally { service.close(); }
    }

    @Test void limitsConnectionsAndReleasesClosedSessions() throws Exception {
        var access = mock(DeveloperAccessService.class);
        var service = new RealtimeLogStreamService(new RealtimeLogBuffer(1000), access, sessions());
        var session = new MockHttpSession(); session.setAttribute(CurrentUserSession.USER_ID, 7L);
        try {
            for (int i = 0; i < 4; i++) service.open(session, null, () -> false);
            assertThat(service.connectionCount()).isEqualTo(4);
            assertThatThrownBy(() -> service.open(session, null, () -> false)).isInstanceOfSatisfying(ResponseStatusException.class,
                    failure -> assertThat(failure.getStatusCode().value()).isEqualTo(429));
            assertThat(service.connectionCount()).isEqualTo(4);
            session.invalidate();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (service.connectionCount() != 0 && System.nanoTime() < deadline) Thread.sleep(20);
            assertThat(service.connectionCount()).isZero();
        } finally { service.close(); }
    }

    @Test void uninitializedResponseCannotAccumulateUnboundedEarlySends() throws Exception {
        var buffer = new RealtimeLogBuffer(1000);
        var service = new RealtimeLogStreamService(buffer, mock(DeveloperAccessService.class), sessions());
        var session = new MockHttpSession(); session.setAttribute(CurrentUserSession.USER_ID, 7L);
        try {
            var emitter = service.open(session, null, () -> false);
            Thread.sleep(50);
            for (int i = 0; i < 5000; i++) buffer.append(logEvent(i));
            Thread.sleep(300);
            var early = (java.util.Collection<?>) org.springframework.test.util.ReflectionTestUtils.getField(emitter, "earlySendAttempts");
            // The single ready frame consists of a few format/data chunks; no log frames enter earlySendAttempts.
            assertThat(early).hasSizeLessThanOrEqualTo(4);
            assertThat(buffer.size()).isEqualTo(1000);
        } finally { service.close(); }
    }

    @Test void slowNetworkWriteCannotBlockApplicationShutdown() throws Exception {
        var blocked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var sends = new java.util.concurrent.atomic.AtomicInteger();
        var completes = new java.util.concurrent.atomic.AtomicInteger();
        var emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(65_000L) {
            @Override public void send(SseEventBuilder builder) throws java.io.IOException {
                if (sends.incrementAndGet() == 1) return;
                blocked.countDown();
                try { release.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new java.io.IOException("connection closed", interrupted); }
            }
            @Override public void complete() { completes.incrementAndGet(); }
        };
        var buffer = new RealtimeLogBuffer(1000); buffer.append(logEvent(1));
        var service = new RealtimeLogStreamService(buffer, mock(DeveloperAccessService.class), sessions()) {
            @Override protected org.springframework.web.servlet.mvc.method.annotation.SseEmitter createEmitter() { return emitter; }
        };
        var session = new MockHttpSession(); session.setAttribute(CurrentUserSession.USER_ID, 7L);
        try (var caller = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            service.open(session, null, () -> true);
            assertThat(blocked.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            caller.submit(service::close).get(1, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(completes.get()).isZero();
            assertThat(service.connectionCount()).isZero();
        } finally { release.countDown(); service.close(); }
    }

    private ch.qos.logback.classic.spi.LoggingEvent logEvent(int number) {
        var context = new ch.qos.logback.classic.LoggerContext();
        var event = new ch.qos.logback.classic.spi.LoggingEvent("test", context.getLogger("test"),
                ch.qos.logback.classic.Level.INFO, "matchId=" + number, null, null);
        event.setMDCPropertyMap(java.util.Map.of());
        return event;
    }

    @Test void authenticationVersionChangeEndsAlreadyOpenStreamEvenIfRemoteSessionWasNotPhysicallyInvalidated() throws Exception {
        var sessions = sessions(); var current = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(sessions.isSessionCurrent(any(), anyLong())).thenAnswer(call -> current.get());
        var service = new RealtimeLogStreamService(new RealtimeLogBuffer(1000), mock(DeveloperAccessService.class), sessions);
        var session = new MockHttpSession(); session.setAttribute(CurrentUserSession.USER_ID, 7L);
        try {
            service.open(session, null, () -> false); assertThat(service.connectionCount()).isEqualTo(1);
            current.set(false);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (service.connectionCount() != 0 && System.nanoTime() < deadline) Thread.sleep(20);
            assertThat(session.isInvalid()).isFalse(); assertThat(service.connectionCount()).isZero();
        } finally { service.close(); }
    }
}
