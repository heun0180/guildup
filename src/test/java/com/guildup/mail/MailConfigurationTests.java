package com.guildup.mail;

import com.guildup.feedback.service.FeedbackMailMessage;
import com.guildup.feedback.service.FeedbackMailService;
import com.guildup.mail.config.ApplicationMailConfig;
import com.guildup.user.verification.EmailVerificationConfig;
import com.guildup.user.verification.EmailVerificationMailService;
import com.guildup.user.verification.EmailVerificationTokens;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MailConfigurationTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withSystemProperties("auth.email-verification.mail-enabled=true")
            .withPropertyValues(
                    "MAIL_HOST=smtp.test.invalid", "MAIL_PORT=2525",
                    "MAIL_USERNAME=test-smtp-user", "MAIL_PASSWORD=test-smtp-password",
                    "MAIL_FROM=noreply@guild-up.com", "MAIL_FROM_NAME=GuildUp",
                    "EMAIL_VERIFICATION_FROM=legacy-sender@gmail.com",
                    "EMAIL_VERIFICATION_URL=https://guild-up.com/email-verification.html",
                    "MAIL_REPLY_TO=", "FEEDBACK_MAIL_TO=heun0180@gmail.com",
                    "spring.mail.test-connection=false")
            .withUserConfiguration(ApplicationMailConfig.class, EmailVerificationConfig.class);

    @Test
    void smtpUsesExistingMailVariablesWithAnyProviderAndRequiredStartTls() {
        runner.withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
                .withPropertyValues("RESEND_API_KEY=")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(JavaMailSender.class);
                    var sender = context.getBean(JavaMailSenderImpl.class);
                    assertThat(sender.getHost()).isEqualTo("smtp.test.invalid");
                    assertThat(sender.getPort()).isEqualTo(2525);
                    assertThat(sender.getUsername()).isEqualTo("test-smtp-user");
                    assertThat(sender.getPassword()).isEqualTo("test-smtp-password");
                    assertThat(sender.getDefaultEncoding()).isEqualTo("UTF-8");
                    assertThat(sender.getJavaMailProperties())
                            .containsEntry("mail.smtp.auth", "true")
                            .containsEntry("mail.smtp.starttls.enable", "true")
                            .containsEntry("mail.smtp.starttls.required", "true")
                            .containsEntry("mail.debug", "false");
                });
    }

    @Test
    void providerSpecificKeyDoesNotOverrideMailPassword() {
        runner.withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
                .withPropertyValues("RESEND_API_KEY=unused-provider-test-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(JavaMailSenderImpl.class).getPassword()).isEqualTo("test-smtp-password");
                });
    }

    @Test
    void absentMailPasswordNeverFallsBackToProviderSpecificKey() {
        runner.withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
                .withPropertyValues("MAIL_PASSWORD=", "RESEND_API_KEY=unused-provider-test-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(JavaMailSenderImpl.class).getPassword()).isEmpty();
                });
    }

    @Test
    void bothMailPathsUseOfficialFromAndKeepFeedbackAndUserRecipientsSeparate() {
        assertMailHeaders(runner, "noreply@guild-up.com", "GuildUp", "heun0180@gmail.com", null);
    }

    @Test
    void explicitReplyToDoesNotChangeOfficialFromOrExistingFeedbackRecipient() {
        assertMailHeaders(runner.withPropertyValues("MAIL_REPLY_TO=support@example.com"),
                "noreply@guild-up.com", "GuildUp", "heun0180@gmail.com", "support@example.com");
    }

    @Test
    void feedbackReceiverChangesOnlyWithAnExplicitReceiverOverride() {
        assertMailHeaders(runner.withPropertyValues("FEEDBACK_MAIL_TO=operator@example.com"),
                "noreply@guild-up.com", "GuildUp", "operator@example.com", null);
    }

    @Test
    void configuredFromAndUtf8DisplayNameAreSharedByBothMailPaths() {
        assertMailHeaders(runner.withPropertyValues("MAIL_FROM=notifications@example.com", "MAIL_FROM_NAME=길드업, 알림"),
                "notifications@example.com", "길드업, 알림", "heun0180@gmail.com", null);
    }

    @Test
    void emptyDisplayNameKeepsAddressOnlyFromHeader() {
        assertMailHeaders(runner.withPropertyValues("MAIL_FROM_NAME="),
                "noreply@guild-up.com", null, "heun0180@gmail.com", null);
    }

    private void assertMailHeaders(ApplicationContextRunner contextRunner, String from, String fromName,
                                   String feedbackTo, String replyTo) {
        var sender = mock(JavaMailSender.class);
        var mime = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(mime);
        contextRunner.withBean(JavaMailSender.class, () -> sender)
                .withUserConfiguration(FeedbackMailService.class, EmailVerificationMailService.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    context.getBean(FeedbackMailService.class).send(new FeedbackMailMessage("문의 제목", "문의 본문"));
                    String token = EmailVerificationTokens.generate();
                    context.getBean(EmailVerificationMailService.class).send("member@example.com", token);

                    var feedback = ArgumentCaptor.forClass(SimpleMailMessage.class);
                    verify(sender).send(feedback.capture());
                    var feedbackFrom = new InternetAddress(feedback.getValue().getFrom());
                    assertThat(feedbackFrom.getAddress()).isEqualTo(from);
                    assertThat(feedbackFrom.getPersonal()).isEqualTo(fromName);
                    assertThat(feedback.getValue().getTo()).containsExactly(feedbackTo);
                    assertThat(feedback.getValue().getReplyTo()).isEqualTo(replyTo);
                    assertThat(feedback.getValue().getSubject()).isEqualTo("문의 제목");
                    assertThat(feedback.getValue().getText()).isEqualTo("문의 본문");

                    verify(sender).send(mime);
                    var verificationFrom = (InternetAddress) mime.getFrom()[0];
                    assertThat(verificationFrom.getAddress()).isEqualTo(from);
                    assertThat(verificationFrom.getPersonal()).isEqualTo(fromName);
                    assertThat(mime.getRecipients(Message.RecipientType.TO)).hasSize(1);
                    assertThat(mime.getRecipients(Message.RecipientType.TO)[0].toString()).isEqualTo("member@example.com");
                    assertThat(mime.getHeader("Reply-To", null)).isEqualTo(replyTo);
                    assertThat(mime.getContent().toString())
                            .contains("https://guild-up.com/email-verification.html#token=" + token, "5분");
                    verify(sender).createMimeMessage();
                    verifyNoMoreInteractions(sender);
                });
    }
}
