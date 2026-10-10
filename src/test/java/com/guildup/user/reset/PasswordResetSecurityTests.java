package com.guildup.user.reset;

import com.guildup.mail.ApplicationMailService;
import com.guildup.mail.config.ApplicationMailProperties;
import com.guildup.monitoring.domain.*;
import com.guildup.monitoring.service.MonitoringEventService;
import com.guildup.user.auth.security.*;
import com.guildup.user.verification.*;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpServletRequest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PasswordResetSecurityTests {
    static PasswordResetProperties properties(Map<String, String> overrides) {
        var values = new HashMap<String, String>(); values.put("auth.password-reset.mail-enabled", "true");
        overrides.forEach((key, value) -> values.put("auth.password-reset." + key, value));
        return new Binder(new MapConfigurationPropertySource(values)).bind("auth.password-reset", Bindable.of(PasswordResetProperties.class)).get();
    }
    static PasswordResetProperties properties() { return properties(Map.of()); }
    @Test void defaultsAndTokenGenerationAreSafeAndPurposeSeparated() {
        assertThat(properties().tokenTtl()).isEqualTo(Duration.ofMinutes(30));
        assertThat(properties().accountHourlyLimit()).isEqualTo(3); assertThat(properties().accountDailyLimit()).isEqualTo(5);
        assertThat(properties().mailDailyBudget()).isEqualTo(30); assertThat(properties().mailMonthlyBudget()).isEqualTo(600);
        var tokens = new HashSet<String>();
        for (int i = 0; i < 1000; i++) {
            String raw = PasswordResetTokens.generate(); assertThat(tokens.add(raw)).isTrue();
            assertThat(Base64.getUrlDecoder().decode(raw)).hasSize(32);
            assertThat(PasswordResetTokens.hash(raw)).hasSize(64).isNotEqualTo(EmailVerificationTokens.hash(raw));
        }
    }
    @Test void rejectsUntrustedUrlShapesAndInvalidDurationsAndLimits() {
        for (String url : List.of("http://evil.example/password-reset.html", "https://user:pass@example.com/password-reset.html",
                "https://example.com/password-reset.html?q=1", "https://example.com/password-reset.html#token=x", "https://example.com/other.html"))
            assertThatThrownBy(() -> properties(Map.of("url", url))).isInstanceOf(BindException.class);
        for (var override : List.of(Map.of("token-ttl", "0s"), Map.of("account-daily-limit", "0"), Map.of("retention", "1d")))
            assertThatThrownBy(() -> properties(override)).isInstanceOf(BindException.class);
        assertThatCode(() -> properties(Map.of("url", "http://localhost:5173/password-reset.html", "allow-local-http", "true"))).doesNotThrowAnyException();
    }
    @Test void resetEmailReusesJavaMailSenderBrandTemplateOfficialSenderAndFragmentWithoutTracking() throws Exception {
        var sender = mock(JavaMailSender.class); var mime = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(mime);
        var from = new ApplicationMailProperties("noreply@guild-up.com", "GuildUp", "", "feedback@example.com");
        var service = new PasswordResetMailService(new ApplicationMailService(sender, from), from, properties());
        String raw = PasswordResetTokens.generate(); service.send("recipient@example.com", raw);
        assertThat(mime.getSubject()).isEqualTo("[GuildUp] 비밀번호 재설정 안내");
        assertThat(mime.getFrom()[0].toString()).isEqualTo("GuildUp <noreply@guild-up.com>");
        assertThat(mime.getContent().toString()).contains("GuildUp", "viewport", "비밀번호 재설정하기", "30분", "한 번 사용", "요청하지 않았다면",
                "https://guild-up.com/password-reset.html#token=" + raw).doesNotContain("<img", "script", "?token=");
        verify(sender).send(mime);
    }
    @Test void disabledMailNeverInvokesSmtp() {
        var sender = mock(JavaMailSender.class); var from = new ApplicationMailProperties("noreply@guild-up.com", "GuildUp", "", "feedback@example.com");
        var service = new PasswordResetMailService(new ApplicationMailService(sender, from), from, properties(Map.of("mail-enabled", "false")));
        assertThatThrownBy(() -> service.send("recipient@example.com", PasswordResetTokens.generate())).isInstanceOf(EmailVerificationMailFailure.Unavailable.class);
        verifyNoInteractions(sender);
    }
    @Test void accountLookupAndMailNeverRunInResponseThreadAndAllAccountRequestsHaveSameResponse() {
        var pending = new ArrayList<Runnable>(); var service = mock(PasswordResetService.class); var events = mock(PasswordResetEvents.class);
        var requests = new PasswordResetRequests(pending::add, service, events);
        var limiter = mock(PasswordResetRequestLimiter.class); when(limiter.reserve(anyString(), anyBoolean())).thenReturn(true);
        var ips = mock(ClientIpResolver.class); when(ips.rateLimitAddress(any())).thenReturn("masked");
        var hasher = mock(LoginIdentityHasher.class); when(hasher.hash(anyString(), anyString())).thenReturn("hash");
        var controller = new PasswordResetController(requests, service, limiter, ips, hasher, events, mock(org.springframework.security.crypto.password.PasswordEncoder.class));
        Object expected = null;
        for (String email : List.of("normal@example.com", "missing@example.com", "discord@example.com")) {
            var response = controller.request(new PasswordResetController.Request(email), new MockHttpServletRequest());
            assertThat(response.getStatusCode().value()).isEqualTo(202);
            if (expected == null) expected = response.getBody(); else assertThat(response.getBody()).isEqualTo(expected);
        }
        assertThat(pending).hasSize(3); verifyNoInteractions(service);
        when(limiter.reserve(anyString(), anyBoolean())).thenReturn(false);
        assertThat(controller.request(new PasswordResetController.Request("normal@example.com"), new MockHttpServletRequest()).getBody()).isEqualTo(expected);
        assertThat(pending).hasSize(3);
    }
    @Test void requestQueueAndStorageFailuresAreGenericAndNeverPropagated() {
        var service = mock(PasswordResetService.class); var events = mock(PasswordResetEvents.class);
        var requests = new PasswordResetRequests(task -> { throw new RejectedExecutionException(); }, service, events);
        assertThatCode(() -> requests.accept("private@example.com")).doesNotThrowAnyException();
        verify(events).record(MonitoringEventCode.PASSWORD_RESET_ABUSE, null); verifyNoInteractions(service);
        var direct = new PasswordResetRequests(Runnable::run, service, events);
        doThrow(new IllegalStateException("DB failure private@example.com")).when(service).issue(anyString());
        assertThatCode(() -> direct.accept("private@example.com")).doesNotThrowAnyException(); verify(events).storageFailed();
    }
    @Test void smtpAndQueueFailuresUseOnlyFixedSafeReasonsAndNeverRetry() throws Exception {
        var delivery = mock(PasswordResetDeliveryTransactions.class); var mail = mock(PasswordResetMailService.class); var events = mock(PasswordResetEvents.class);
        when(delivery.begin(1L)).thenReturn(Optional.of(new PasswordResetDeliveryTransactions.Recipient(10L, "private@example.com")));
        doThrow(new org.springframework.mail.MailSendException("private@example.com #token=secret")).when(mail).send(anyString(), anyString());
        var listener = new PasswordResetMailListener(Runnable::run, delivery, mail, events);
        assertThatCode(() -> listener.committed(new PasswordResetService.MailRequested(1L, "secret"))).doesNotThrowAnyException();
        verify(delivery).finish(1L, false); verify(events).mailFailed(10L, EmailVerificationMailFailure.SMTP_SEND_FAILED);
        verify(mail, times(1)).send(anyString(), anyString());
        var full = new PasswordResetMailListener(task -> { throw new RejectedExecutionException(); }, delivery, mail, events);
        assertThatCode(() -> full.committed(new PasswordResetService.MailRequested(2L, "secret"))).doesNotThrowAnyException();
        verify(events).mailFailed(null, EmailVerificationMailFailure.QUEUE_FULL);
    }
    @Test void deliveryDbFailureIsRecoverableAndNeverRetriesSmtp() throws Exception {
        var delivery = mock(PasswordResetDeliveryTransactions.class); var mail = mock(PasswordResetMailService.class); var events = mock(PasswordResetEvents.class);
        when(delivery.begin(1L)).thenThrow(new IllegalStateException("private@example.com #token=secret"));
        doThrow(new IllegalStateException("storage failed")).when(delivery).finish(1L, false);
        assertThatCode(() -> new PasswordResetMailListener(Runnable::run, delivery, mail, events).committed(new PasswordResetService.MailRequested(1L, "secret")))
                .doesNotThrowAnyException();
        verifyNoInteractions(mail); verify(events).mailFailed(null, EmailVerificationMailFailure.DELIVERY_STORAGE_FAILED);
    }
    @Test void ipRequestAndTokenLimitsAreIndependentBoundedAndExpireWithoutExtendingDenials() {
        Clock clock = mock(Clock.class); when(clock.millis()).thenReturn(1000000L);
        var properties = properties(Map.of("ip-hourly-limit", "3", "ip-daily-limit", "5", "token-requests-per-minute", "2", "max-ip-entries", "2"));
        var limiter = new PasswordResetRequestLimiter(properties, clock);
        for (int i = 0; i < 3; i++) assertThat(limiter.reserve("same", false)).isTrue();
        assertThat(limiter.reserve("same", false)).isFalse();
        assertThat(limiter.reserve("same", true)).isTrue(); assertThat(limiter.reserve("same", true)).isTrue();
        assertThat(limiter.reserve("same", true)).isFalse(); assertThat(limiter.reserve("other", false)).isFalse();
        when(clock.millis()).thenReturn(1000000L + 3600000);
        assertThat(limiter.reserve("same", false)).isTrue(); assertThat(limiter.reserve("same", false)).isTrue(); assertThat(limiter.reserve("same", false)).isFalse();
        when(clock.millis()).thenReturn(1000000L + 86400000 * 2);
        assertThat(limiter.reserve("other", false)).isTrue();
    }
    @Test void ipConcurrentRequestsCannotExceedBudgetAndCountMultipleEmailTargetsTogether() throws Exception {
        var limiter = new PasswordResetRequestLimiter(properties(Map.of("ip-hourly-limit", "3")), Clock.systemUTC());
        var winners = PasswordResetFlowTests.concurrent(20, () -> limiter.reserve("one-ip", false));
        assertThat(Collections.frequency(winners, true)).isEqualTo(3);
    }
    @Test void monitoringIsBoundedSafeAndItsFailureCannotStopReset() {
        var monitoring = mock(MonitoringEventService.class); var events = new PasswordResetEvents(monitoring, Clock.systemUTC(), properties(Map.of("max-events-per-minute", "3")));
        for (int i = 0; i < 1000; i++) events.record(MonitoringEventCode.PASSWORD_RESET_INVALID, null);
        verify(monitoring, times(3)).recordWarn(eq(MonitoringCategory.SECURITY), eq(MonitoringEventCode.PASSWORD_RESET_INVALID),
                eq("Password reset request rejected"), isNull(), isNull(), isNull(), eq(Map.of()));
        var broken = mock(MonitoringEventService.class);
        doThrow(new IllegalStateException("private@example.com secret")).when(broken).recordInfo(any(), any(), anyString(), any(), any(), any(), anyMap());
        assertThatCode(() -> new PasswordResetEvents(broken, Clock.systemUTC(), properties()).record(MonitoringEventCode.PASSWORD_RESET_COMPLETED, 1L)).doesNotThrowAnyException();
    }
    @Test void secretBearingObjectsNeverExposeInputsInToString() {
        for (Object value : List.of(new PasswordResetController.Request("private@example.com"), new PasswordResetController.Validation("secret"),
                new PasswordResetController.Confirmation("secret", "password", "password"), new PasswordResetService.MailRequested(1L, "secret"),
                new PasswordResetDeliveryTransactions.Recipient(1L, "private@example.com")))
            assertThat(value.toString()).doesNotContain("private@example.com", "secret", "password");
    }
}
