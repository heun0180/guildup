package com.guildup.user.auth.security;

import com.guildup.monitoring.domain.*;
import com.guildup.monitoring.repository.MonitoringEventRepository;
import com.guildup.monitoring.service.*;
import com.guildup.user.auth.dto.EmailLoginRequest;
import com.guildup.user.auth.exception.AuthException;
import com.guildup.user.auth.service.CredentialAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import java.time.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoginSecurityIsolationTests {
    @Test void blockedAsyncWriterAndFullQueueDoNotDelayOrReplaceLoginResult() throws Exception {
        var clock = Clock.systemUTC();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var writer = mock(MonitoringEventWriter.class);
        doAnswer(invocation -> {
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            throw new IllegalStateException("test DB offline");
        })
                .when(writer).write(any(), any(), any(), anyString(), any(), any(), any(), anyMap(), any());
        var pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.AbortPolicy());
        var monitoring = new MonitoringEventService(writer, mock(MonitoringEventRepository.class), new SafeMonitoringDataSanitizer(), clock, pool, true);
        var policy = new LoginProtectionProperties(); policy.setAccountFreeFailures(1);
        var credentials = mock(CredentialAuthService.class);
        when(credentials.login(any())).thenThrow(new AuthException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다."));
        var login = new ProtectedEmailLoginService(credentials, new InMemoryLoginAttemptStore(policy, clock),
                new ClientIpResolver(new TrustedProxyProperties(), policy), new LoginIdentityHasher(policy), new LoginSecurityEvents(monitoring, clock, 60));
        var request = new MockHttpServletRequest(); request.setRemoteAddr("203.0.113.1");
        var body = new EmailLoginRequest("private@example.com", "private-password");
        try {
            for (int i = 0; i < 2; i++) assertThatThrownBy(() -> login.login(body, request)).isInstanceOf(AuthException.class)
                    .extracting("code").isEqualTo("INVALID_CREDENTIALS");
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            try (var caller = Executors.newSingleThreadExecutor()) {
                assertThat(caller.submit(() -> {
                    try { login.login(body, request); return "unexpected"; }
                    catch (AuthException failure) { return failure.getCode(); }
                }).get(1, TimeUnit.SECONDS)).isEqualTo("LOGIN_RATE_LIMITED");
                // Another identity emits a signal while the worker and queue are occupied.
                var second = new EmailLoginRequest("another@example.com", "private-password");
                caller.submit(() -> { for (int i = 0; i < 2; i++) try { login.login(second, request); } catch (AuthException ignored) {} }).get(1, TimeUnit.SECONDS);
            }
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void monitoringFailureCannotBreakSuccessfulLoginAndGlobalSamplingIsBounded() {
        var clock = new InMemoryLoginAttemptStoreTests.MutableClock();
        var monitoring = mock(MonitoringEventService.class);
        doThrow(new IllegalStateException("test writer failure")).when(monitoring).recordWarn(any(), any(), anyString(), any(), any(), any(), anyMap());
        var signals = new LoginSecurityEvents(monitoring, clock, 2);
        var signal = new LoginAttemptStore.Signal(LoginAttemptStore.Kind.RATE_LIMITED, "IP", 100, 5, 3, true, 2);
        for (int i = 0; i < 100; i++) signals.record(signal, "account-hmac", "ip-hmac");
        verify(monitoring, times(2)).recordWarn(eq(MonitoringCategory.SECURITY), any(), anyString(), any(), any(), any(), anyMap());
        clock.advance(Duration.ofMinutes(1)); signals.record(signal, "account-hmac", "ip-hmac");
        verify(monitoring, times(3)).recordWarn(any(), any(), anyString(), any(), any(), any(), anyMap());
        var credentials = mock(CredentialAuthService.class); var user = new com.guildup.user.domain.User("test");
        when(credentials.login(any())).thenReturn(user);
        var store = mock(LoginAttemptStore.class); var permit = mock(LoginAttemptStore.Permit.class);
        when(store.begin(anyString(), anyString())).thenReturn(new LoginAttemptStore.Decision(permit, 0, java.util.List.of(signal)));
        when(store.complete(any(), any())).thenReturn(java.util.List.of());
        var policy = new LoginProtectionProperties();
        var login = new ProtectedEmailLoginService(credentials, store, new ClientIpResolver(new TrustedProxyProperties(), policy), new LoginIdentityHasher(policy), signals);
        assertThat(login.login(new EmailLoginRequest("member@example.com", "secret"), new MockHttpServletRequest())).isSameAs(user);
        verify(store).complete(permit, LoginAttemptStore.Outcome.SUCCESS);
    }

    @Test void keyedIdentifiersNormalizeNamespacesAndDoNotContainRawPrivateValues() {
        var policy = new LoginProtectionProperties(); policy.setHmacSecret("test-only-hmac-secret-at-least-32-bytes");
        var hash = new LoginIdentityHasher(policy);
        assertThat(hash.hash("account", "private@example.com")).matches("[a-f0-9]{64}")
                .isEqualTo(new LoginIdentityHasher(policy).hash("account", "private@example.com"))
                .isNotEqualTo(hash.hash("ip", "private@example.com"));
        policy.setHmacSecret("too-short"); assertThatThrownBy(() -> new LoginIdentityHasher(policy)).isInstanceOf(IllegalArgumentException.class);
    }
}
