package com.guildup.feedback.service;

import com.guildup.feedback.exception.FeedbackMailException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class FeedbackMailService {
    private static final Logger log = LoggerFactory.getLogger(FeedbackMailService.class);
    private static final String FEEDBACK_RECEIVER = "heun0180@gmail.com";

    private final JavaMailSender mailSender;
    private final String sender;

    public FeedbackMailService(JavaMailSender mailSender,
                               @Value("${spring.mail.username}") String sender) {
        this.mailSender = mailSender;
        this.sender = sender;
    }

    public void send(FeedbackMailMessage feedback) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(sender);
            message.setTo(FEEDBACK_RECEIVER);
            message.setSubject(feedback.subject());
            message.setText(feedback.body());
            mailSender.send(message);
        } catch (MailException | IllegalArgumentException exception) {
            log.error("GuildUp feedback email delivery failed", exception);
            throw new FeedbackMailException(exception);
        }
    }
}
