package com.guildup.mail.config;

import jakarta.mail.internet.InternetAddress;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

/** SMTP 인증 정보와 독립적인 공통 발신 주소, 회신 주소, 문의 알림 수신처. */
@ConfigurationProperties("app.mail")
public record ApplicationMailProperties(
        @DefaultValue("noreply@guild-up.com") String from,
        @DefaultValue("GuildUp") String fromName,
        @DefaultValue("") String replyTo,
        @DefaultValue("heun0180@gmail.com") String feedbackTo) {

    /** 한글과 쉼표 등 표시 이름을 메일 주소의 헤더 문법에 맞춰 인코딩한다. */
    public String fromHeader() {
        if (fromName == null || fromName.isBlank()) return from;
        try {
            return new InternetAddress(from, fromName, StandardCharsets.UTF_8.name()).toString();
        } catch (UnsupportedEncodingException exception) {
            throw new IllegalArgumentException("Cannot encode mail sender name as UTF-8", exception);
        }
    }
}
