package com.guildup.user.verification;

import com.guildup.mail.config.ApplicationMailConfig;
import com.guildup.mail.config.ApplicationMailProperties;
import com.guildup.monitoring.domain.*;
import com.guildup.monitoring.service.MonitoringEventService;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mail.javamail.JavaMailSender;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmailVerificationSecurityTests {
    static EmailVerificationProperties properties(URI url, int maxEntries) {
        return new EmailVerificationProperties(true, false, url, Duration.ofSeconds(300), Duration.ofSeconds(60),
                3, 5, 3, 5, 2, maxEntries, 3, Duration.ofDays(7), Duration.ofMinutes(10), false);
    }
    static EmailVerificationProperties properties() { return properties(URI.create("https://example.com/email-verification.html"), 100); }
    static ApplicationMailProperties mailProperties() {
        return new ApplicationMailProperties("noreply@guild-up.com", "", "", "heun0180@gmail.com");
    }
    @Test void configurationBindingDefaultsTo300Seconds() {
        var binder = new Binder(new MapConfigurationPropertySource(Map.of("auth.email-verification.mail-enabled", "true")));
        assertThat(binder.bind("auth.email-verification", Bindable.of(EmailVerificationProperties.class)).get().tokenTtl())
                .isEqualTo(Duration.ofSeconds(300));
    }
    @Test void generatedTokensAreUniqueUnpredictableAndHave256BitsAndHashOnly() {
        var values = new HashSet<String>();
        for (int i = 0; i < 1000; i++) {
            String raw = EmailVerificationTokens.generate(); assertThat(values.add(raw)).isTrue();
            assertThat(Base64.getUrlDecoder().decode(raw)).hasSize(32);
            assertThat(EmailVerificationTokens.hash(raw)).hasSize(64).doesNotContain(raw);
        }
        assertThat(EmailVerificationTokens.validFormat("wrong")).isFalse();
    }
    @Test void insecureOrAmbiguousUrlsAreRejected() {
        for (String url : List.of("http://example.com/verify", "https://user:pass@example.com/verify", "https://example.com/verify?q=x", "https://example.com/verify#x"))
            assertThatThrownBy(() -> properties(URI.create(url), 100)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void localHttpExceptionOnlyAllowsLiteralLoopbackAddressesAndExplicitLocalProfile() {
        for (String host : List.of("localhost", "127.0.0.1", "[::1]")) {
            var local = new Binder(new MapConfigurationPropertySource(Map.of(
                    "auth.email-verification.url", "http://" + host + ":5173/email-verification.html",
                    "auth.email-verification.allow-local-http", "true")))
                    .bind("auth.email-verification", Bindable.of(EmailVerificationProperties.class)).get();
            var environment = new org.springframework.mock.env.MockEnvironment();
            assertThatThrownBy(() -> new EmailVerificationConfig(local, environment)).isInstanceOf(IllegalArgumentException.class);
            environment.setActiveProfiles("local");
            assertThatCode(() -> new EmailVerificationConfig(local, environment)).doesNotThrowAnyException();
            environment.setActiveProfiles("prod", "local");
            assertThatThrownBy(() -> new EmailVerificationConfig(local, environment)).isInstanceOf(IllegalArgumentException.class);
        }
        for (String url : List.of("http://example.com/verify", "http://localhost.evil.test/verify", "http://0.0.0.0/verify",
                "http://user:pass@localhost/verify", "http://localhost/verify?q=x", "http://localhost/verify#x")) {
            assertThatThrownBy(() -> new Binder(new MapConfigurationPropertySource(Map.of(
                    "auth.email-verification.url", url, "auth.email-verification.allow-local-http", "true")))
                    .bind("auth.email-verification", Bindable.of(EmailVerificationProperties.class)))
                    .isInstanceOf(org.springframework.boot.context.properties.bind.BindException.class);
        }
    }
    @Test void localProfileCanSendMailWithLoopbackFragmentAndFiveMinuteValidity() {
        var sender = mock(JavaMailSender.class);
        var mime = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(mime);
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withSystemProperties("auth.email-verification.mail-enabled=true")
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .withUserConfiguration(ApplicationMailConfig.class, EmailVerificationConfig.class, EmailVerificationMailService.class)
                .withBean(JavaMailSender.class, () -> sender)
                .withPropertyValues("auth.email-verification.url=http://localhost:5173/email-verification.html",
                        "auth.email-verification.allow-local-http=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var service = context.getBean(EmailVerificationMailService.class);
                    assertThat(service.configurationFailure()).isEmpty();
                    String raw = EmailVerificationTokens.generate();
                    service.send("recipient@example.com", raw);
                    assertThat(mime.getContent().toString()).contains("http://localhost:5173/email-verification.html#token=" + raw, "5분");
                    verify(sender).send(mime);
                });
    }
    @Test void ipMailAndConfirmBudgetsAreSeparateAndExpireWithoutExtendingOnDeniedRequest() {
        Clock clock = mock(Clock.class); when(clock.millis()).thenReturn(1000000L);
        var limiter = new EmailVerificationIpLimiter(properties(), clock);
        for (int i = 0; i < 3; i++) assertThat(limiter.reserve("ip", false)).isZero();
        assertThat(limiter.reserve("ip", false)).isEqualTo(3600);
        assertThat(limiter.reserve("ip", true)).isZero(); assertThat(limiter.reserve("ip", true)).isZero();
        assertThat(limiter.reserve("ip", true)).isEqualTo(60);
        when(clock.millis()).thenReturn(1000000L + 3600000);
        assertThat(limiter.reserve("ip", false)).isZero(); assertThat(limiter.reserve("ip", false)).isZero();
        assertThat(limiter.reserve("ip", false)).isEqualTo(82800);
    }
    @Test void saturatedIpStoreFailsClosedAndExpiredEntriesAreReclaimed() {
        Clock clock = mock(Clock.class); when(clock.millis()).thenReturn(1000000L);
        var limiter = new EmailVerificationIpLimiter(properties(URI.create("https://example.com/verify"), 1), clock);
        assertThat(limiter.reserve("first", false)).isZero(); assertThat(limiter.reserve("other", false)).isEqualTo(60);
        when(clock.millis()).thenReturn(1000000L + 86400000);
        assertThat(limiter.reserve("other", false)).isZero();
    }
    @Test void concurrentIpRequestsNeverExceedBudget() throws Exception {
        var limiter = new EmailVerificationIpLimiter(properties(), Clock.systemUTC());
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Future<Long>> results = new ArrayList<>();
            for (int i = 0; i < 40; i++) results.add(pool.submit(() -> limiter.reserve("same", true)));
            long winners = 0; for (var result : results) if (result.get() == 0) winners++;
            assertThat(winners).isEqualTo(2);
        }
    }
    @Test void monitoringHasGlobalWriteCeilingAndNoSecretFields() {
        var monitoring = mock(MonitoringEventService.class); var events = new EmailVerificationEvents(monitoring, Clock.systemUTC(), properties());
        for (int i = 0; i < 1000; i++) events.record(MonitoringEventCode.EMAIL_VERIFICATION_INVALID, null);
        verify(monitoring, times(3)).recordWarn(eq(MonitoringCategory.SECURITY), eq(MonitoringEventCode.EMAIL_VERIFICATION_INVALID),
                eq("Email verification request rejected"), isNull(), isNull(), isNull(), eq(Map.of()));
        verifyNoMoreInteractions(monitoring);
    }
    @Test void htmlMailUsesConfiguredHttpsFragmentAndNeverCallsSenderUntilExplicitSend() throws Exception {
        var sender = mock(JavaMailSender.class); var mime = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(mime);
        var service = new EmailVerificationMailService(sender, properties(), mailProperties()); verifyNoInteractions(sender);
        String raw = EmailVerificationTokens.generate(); service.send("recipient@example.com", raw);
        assertThat(mime.getSubject()).isEqualTo("[GuildUp] 이메일 인증을 완료해 주세요.");
        assertThat(mime.getFrom()[0].toString()).isEqualTo("noreply@guild-up.com");
        assertThat(mime.getHeader("Reply-To")).isNull();
        assertThat(mime.getContent().toString()).contains("https://example.com/email-verification.html#token=" + raw, "5분", "viewport", "이메일 인증하기");
        verify(sender).send(mime);
    }
    @Test void smtpExceptionsAreHandledByListenerWithNoMessageOrStackForwarded() throws Exception {
        var delivery = mock(EmailVerificationDeliveryTransactions.class); var mail = mock(EmailVerificationMailService.class);
        var events = mock(EmailVerificationEvents.class);
        when(delivery.begin(1L)).thenReturn(Optional.of(new EmailVerificationDeliveryTransactions.Recipient(10L, "private@example.com")));
        doThrow(new MailSendException("private@example.com token=secret")).when(mail).send(anyString(), anyString());
        var listener = new EmailVerificationMailListener(Runnable::run, delivery, mail, events);
        assertThatCode(() -> listener.committed(new EmailVerificationService.MailRequested(1L, "secret"))).doesNotThrowAnyException();
        verify(delivery).finish(1L, false); verify(events).mailFailed(10L, EmailVerificationMailFailure.SMTP_SEND_FAILED);
    }
    @Test void fullMailQueueCannotRollBackCommittedRegistration() {
        var delivery = mock(EmailVerificationDeliveryTransactions.class); var events = mock(EmailVerificationEvents.class);
        var listener = new EmailVerificationMailListener(task -> { throw new RejectedExecutionException(); }, delivery, mock(EmailVerificationMailService.class), events);
        assertThatCode(() -> listener.committed(new EmailVerificationService.MailRequested(1L, "secret"))).doesNotThrowAnyException();
        verify(delivery).finish(1L, false); verify(events).mailFailed(null, EmailVerificationMailFailure.QUEUE_FULL);
    }
    @Test void secretBearingObjectsHaveSafeStringRepresentations() {
        assertThat(new EmailVerificationService.MailRequested(1L, "raw-secret").toString()).doesNotContain("raw-secret");
        assertThat(new EmailVerificationController.Confirmation("raw-secret").toString()).doesNotContain("raw-secret");
        assertThat(new EmailVerificationDeliveryTransactions.Recipient(1L, "private@example.com").toString()).doesNotContain("private@example.com");
    }
    @Test void unconfiguredPlaceholderUrlCannotSendLinksToAnAssumedDomain() {
        var sender = mock(JavaMailSender.class);
        var service = new EmailVerificationMailService(sender, properties(URI.create("https://guildup.example/email-verification.html"), 100), mailProperties());
        assertThat(service.configurationFailure()).contains(EmailVerificationMailFailure.URL_NOT_CONFIGURED);
        assertThatThrownBy(() -> service.send("recipient@example.com", EmailVerificationTokens.generate()))
                .isInstanceOfSatisfying(EmailVerificationMailFailure.Unavailable.class,
                        failure -> assertThat(failure.reason()).isEqualTo(EmailVerificationMailFailure.URL_NOT_CONFIGURED));
        verifyNoInteractions(sender);
    }
    @Test void smtpFailureClassificationUsesExceptionTypesAndNeverMessages() {
        assertThat(EmailVerificationMailFailure.classify(new MailAuthenticationException("private@example.com password=secret")))
                .isEqualTo(EmailVerificationMailFailure.SMTP_AUTHENTICATION_FAILED);
        var timeout = new jakarta.mail.MessagingException("private@example.com #token=secret", new java.net.SocketTimeoutException("private URL"));
        assertThat(EmailVerificationMailFailure.classify(new MailSendException(Map.of("private message", timeout))))
                .isEqualTo(EmailVerificationMailFailure.SMTP_TIMEOUT);
        assertThat(EmailVerificationMailFailure.classify(new MailSendException("private URL", new java.net.ConnectException())))
                .isEqualTo(EmailVerificationMailFailure.SMTP_CONNECTION_FAILED);
        var auth = new jakarta.mail.AuthenticationFailedException("secret");
        assertThat(EmailVerificationMailFailure.classify(new MailSendException(Map.of("private message", auth))))
                .isEqualTo(EmailVerificationMailFailure.SMTP_AUTHENTICATION_FAILED);
    }
    @Test void failureDiagnosticsShareRateLimitAndRemainAvailableWithoutMonitoringDatabase() {
        var monitoring = mock(MonitoringEventService.class);
        doThrow(new IllegalStateException("private@example.com #token=secret")).when(monitoring)
                .recordError(any(), any(), anyString(), any(), any(), any(), anyMap());
        var events = new EmailVerificationEvents(monitoring, Clock.systemUTC(), properties());
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(EmailVerificationEvents.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            events.record(MonitoringEventCode.EMAIL_VERIFICATION_INVALID, 10L);
            for (int i = 0; i < 100; i++) events.mailFailed(10L, EmailVerificationMailFailure.URL_NOT_CONFIGURED);
            verify(monitoring, times(2)).recordError(eq(MonitoringCategory.SECURITY), eq(MonitoringEventCode.EMAIL_VERIFICATION_MAIL_FAILED),
                    eq("Verification email delivery failed"), isNull(), eq(10L), isNull(), eq(Map.of("reason", "URL_NOT_CONFIGURED")));
            assertThat(appender.list).hasSize(2).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).contains("URL_NOT_CONFIGURED").doesNotContain("private@example.com", "secret", "#token");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
}
