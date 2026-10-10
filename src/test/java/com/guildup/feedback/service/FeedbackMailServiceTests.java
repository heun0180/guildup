package com.guildup.feedback.service;

import com.guildup.mail.config.ApplicationMailProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeedbackMailServiceTests {
    @Test
    void sendsFromOfficialAddressToExistingDeveloperMailbox() {
        JavaMailSender sender = mock(JavaMailSender.class);
        FeedbackMailService service = new FeedbackMailService(sender,
                new ApplicationMailProperties("noreply@guild-up.com", "", "", "heun0180@gmail.com"));

        service.send(new FeedbackMailMessage("문의 제목", "문의 본문"));

        var captor = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(captor.capture());
        assertThat(captor.getValue().getFrom()).isEqualTo("noreply@guild-up.com");
        assertThat(captor.getValue().getTo()).containsExactly("heun0180@gmail.com");
        assertThat(captor.getValue().getReplyTo()).isNull();
        assertThat(captor.getValue().getSubject()).isEqualTo("문의 제목");
        assertThat(captor.getValue().getText()).isEqualTo("문의 본문");
    }

    @Test
    void wrapsMailTransportFailure() {
        JavaMailSender sender = mock(JavaMailSender.class);
        FeedbackMailService service = new FeedbackMailService(sender,
                new ApplicationMailProperties("noreply@guild-up.com", "", "", "heun0180@gmail.com"));
        doThrow(new org.springframework.mail.MailSendException("SMTP failed"))
                .when(sender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> service.send(new FeedbackMailMessage("제목", "본문")))
                .isInstanceOf(com.guildup.feedback.exception.FeedbackMailException.class)
                .hasCauseInstanceOf(org.springframework.mail.MailSendException.class);
    }
}
