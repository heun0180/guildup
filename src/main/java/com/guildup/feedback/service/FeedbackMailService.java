package com.guildup.feedback.service;

import com.guildup.feedback.exception.FeedbackMailException;
import com.guildup.mail.config.ApplicationMailProperties;
import com.guildup.monitoring.logging.FailureLogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class FeedbackMailService {
    private static final Logger log = LoggerFactory.getLogger(FeedbackMailService.class);
    private final JavaMailSender mailSender;
    private final ApplicationMailProperties properties;

    public FeedbackMailService(JavaMailSender mailSender, ApplicationMailProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    public void send(FeedbackMailMessage feedback) {
        long started = System.nanoTime();
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(properties.fromHeader());
            message.setTo(properties.feedbackTo());
            if (properties.replyTo() != null && !properties.replyTo().isBlank()) {
                message.setReplyTo(properties.replyTo());
            }
            message.setSubject(feedback.subject());
            message.setText(feedback.body());
            mailSender.send(message);
        } catch (MailException | IllegalArgumentException exception) {
            log.error("Feedback email delivery failed. jobName=feedbackEmail, elapsedMs={}",
                    (System.nanoTime() - started) / 1_000_000, exception);
            FeedbackMailException failure = new FeedbackMailException(exception);
            FailureLogContext.markLogged(failure);
            throw failure;
        }
    }
}
