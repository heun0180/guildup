package com.guildup.mail;

import com.guildup.mail.config.ApplicationMailProperties;
import jakarta.mail.MessagingException;
import org.springframework.mail.javamail.*;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

/** 인증/재설정 메일이 공유하는 SMTP, 발신 헤더와 모바일 HTML 템플릿. SMTP 예외를 로깅하지 않는다. */
@Service
public class ApplicationMailService {
    private final JavaMailSender sender;
    private final ApplicationMailProperties properties;
    public ApplicationMailService(JavaMailSender sender, ApplicationMailProperties properties) {
        this.sender = sender; this.properties = properties;
    }
    public void sendAction(String email, String subject, String greeting, String instruction,
                           String button, String url, String validity) throws MessagingException {
        var mime = sender.createMimeMessage();
        var message = new MimeMessageHelper(mime, false, "UTF-8");
        message.setFrom(properties.fromHeader());
        if (properties.replyTo() != null && !properties.replyTo().isBlank()) message.setReplyTo(properties.replyTo());
        message.setTo(email);
        message.setSubject(subject);
        message.setText("""
            <!doctype html><html lang="ko"><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
            <body style="margin:0;background:#f4f6fb;font-family:Arial,sans-serif;color:#202638;line-height:1.7">
            <table role="presentation" width="100%%" cellpadding="0" cellspacing="0"><tr><td style="padding:24px 12px">
            <table role="presentation" align="center" width="100%%" style="max-width:520px;background:#fff;border-radius:16px" cellpadding="24">
            <tr><td><h1 style="font-size:24px;color:#5865f2">GuildUp</h1><p>안녕하세요.<br>%s</p>
            <p>%s</p><p style="margin:28px 0"><a href="%s" style="display:inline-block;padding:12px 24px;background:#5865f2;color:#fff;text-decoration:none;border-radius:8px">%s</a></p>
            <p>%s</p><p style="font-size:14px;color:#666">본인이 요청하지 않았다면 이 메일을 무시해 주세요.<br>GuildUp</p>
            </td></tr></table></td></tr></table></body></html>
            """.formatted(escape(greeting), escape(instruction), HtmlUtils.htmlEscape(url), escape(button), escape(validity)), true);
        sender.send(mime);
    }
    private static String escape(String value) { return HtmlUtils.htmlEscape(value).replace("\n", "<br>"); }
}
