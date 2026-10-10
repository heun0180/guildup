package com.guildup.monitoring.service;

import com.guildup.developer.config.DeveloperAccessService;
import com.guildup.monitoring.logging.RealtimeLogBuffer;
import com.guildup.user.auth.service.CurrentUserSession;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class RealtimeLogStreamService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RealtimeLogStreamService.class);
    private static final int MAX_CONNECTIONS = 4;
    private static final long STREAM_MILLIS = 60_000;
    private final RealtimeLogBuffer buffer;
    private final DeveloperAccessService access;
    private final com.guildup.user.auth.service.AuthSessionService sessions;
    // Fixed slots + no queue: stalled clients can never create an unbounded backlog of send tasks.
    private final ThreadPoolExecutor senders = new ThreadPoolExecutor(MAX_CONNECTIONS, MAX_CONNECTIONS,
            0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "monitoring-log-stream");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final Map<SseEmitter, AtomicBoolean> emitters = new ConcurrentHashMap<>();

    public RealtimeLogStreamService(RealtimeLogBuffer buffer, DeveloperAccessService access,
                                   com.guildup.user.auth.service.AuthSessionService sessions) {
        this.buffer = buffer;
        this.access = access;
        this.sessions = sessions;
    }

    public SseEmitter open(HttpSession session, String lastId, java.util.function.BooleanSupplier responseCommitted) {
        Long userId;
        try { userId = CurrentUserSession.requireUserId(session); }
        catch (IllegalStateException invalidatedSession) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인 후 이용해 주세요.");
        }
        access.requireSystemAdmin(userId); // Defense in depth in addition to DeveloperAccessInterceptor.
        if (!sessions.isSessionCurrent(session, userId))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인 후 이용해 주세요.");
        SseEmitter emitter = createEmitter();
        AtomicBoolean active = new AtomicBoolean(true);
        Runnable remove = () -> { active.set(false); emitters.remove(emitter); };
        emitter.onCompletion(remove);
        // Spring/container owns timeout completion; never contend with a stalled send's write lock here.
        emitter.onTimeout(remove);
        emitter.onError(failure -> remove.run());
        emitters.put(emitter, active);
        try { senders.execute(() -> stream(emitter, active, session, userId, lastId, responseCommitted)); }
        catch (RejectedExecutionException full) {
            remove.run();
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "실시간 로그 연결 수를 초과했습니다.");
        }
        return emitter;
    }

    protected SseEmitter createEmitter() { return new SseEmitter(STREAM_MILLIS + 5000); }

    private void stream(SseEmitter emitter, AtomicBoolean active, HttpSession session, Long userId, String lastId,
                        java.util.function.BooleanSupplier responseCommitted) {
        long started = System.nanoTime();
        long lastHeartbeat = started;
        String cursor = lastId;
        try {
            emitter.send(SseEmitter.event().name("ready").reconnectTime(3000).data(Map.of("capacity", buffer.capacity())));
            while (active.get() && elapsed(started) < STREAM_MILLIS) {
                // Logout ends delivery immediately; role changes are checked every heartbeat.
                if (!sessionStillValid(session, userId)) break;
                // Password reset on another instance revokes already-open streams on the next 250ms poll.
                if (!sessions.isSessionCurrent(session, userId)) break;
                // The ready frame is the only pre-initialization send. Otherwise Spring's
                // earlySendAttempts collection could bypass the bounded application buffer.
                if (!responseCommitted.getAsBoolean()) { Thread.sleep(250); continue; }
                if (elapsed(lastHeartbeat) >= 15_000) {
                    access.requireSystemAdmin(userId);
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                    lastHeartbeat = System.nanoTime();
                }
                var batch = buffer.read(cursor, 200);
                if (batch.gap()) {
                    emitter.send(SseEmitter.event().name("gap").data(Map.of("message", "일부 오래된 로그가 제거되었습니다.")));
                    if (batch.entries().isEmpty()) cursor = null;
                }
                for (var entry : batch.entries()) {
                    if (!active.get() || elapsed(started) >= STREAM_MILLIS || !sessionStillValid(session, userId)) break;
                    if (elapsed(lastHeartbeat) >= 15_000) {
                        access.requireSystemAdmin(userId);
                        lastHeartbeat = System.nanoTime();
                    }
                    emitter.send(SseEmitter.event().name("log").id(entry.id()).data(entry));
                    cursor = entry.id();
                }
                Thread.sleep(250);
            }
            emitter.complete();
        } catch (IOException disconnected) {
            // Spring performs the final async dispatch after a failed write; no recursive error logging.
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            emitter.complete();
        } catch (ResponseStatusException denied) {
            if (!denied.getStatusCode().is4xxClientError())
                log.warn("Live log authorization check failed - jobName=liveLogStream userId={} stage=AUTHORIZE", userId, denied);
            emitter.complete();
        } catch (RuntimeException failure) {
            if (active.get() && !org.springframework.web.util.DisconnectedClientHelper.isClientDisconnectedException(failure))
                log.warn("Live log subscription failed - jobName=liveLogStream userId={} stage=SEND", userId, failure);
            emitter.complete();
        } finally {
            active.set(false);
            emitters.remove(emitter);
        }
    }

    private boolean sessionStillValid(HttpSession session, Long userId) {
        try { return userId.equals(CurrentUserSession.requireUserId(session)); }
        catch (ResponseStatusException | IllegalStateException invalidatedSession) { return false; }
    }

    private long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }
    public int connectionCount() { return emitters.size(); }

    @PreDestroy public void close() {
        // Do not block the application shutdown thread behind a slow network client's write lock.
        emitters.values().forEach(active -> active.set(false));
        emitters.clear();
        senders.shutdownNow();
    }
}
