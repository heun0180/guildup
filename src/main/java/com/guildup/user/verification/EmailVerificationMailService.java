package com.guildup.user.verification;

import com.guildup.mail.config.ApplicationMailProperties;
import jakarta.mail.MessagingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.mail.javamail.*;
import org.springframework.stereotype.Service;
import com.guildup.mail.ApplicationMailService;
import java.util.Locale;
import java.util.Optional;

/** 문의 메일의 수신자/본문/오류 정책과 독립적이며 SMTP와 공통 발신 설정을 공유한다. */
@Service
public class EmailVerificationMailService {
    private static final Logger log = LoggerFactory.getLogger(EmailVerificationMailService.class);
    private final ApplicationMailService mail;
    private final EmailVerificationProperties properties;
    private final ApplicationMailProperties mailProperties;
    public EmailVerificationMailService(JavaMailSender sender, EmailVerificationProperties properties,
                                        ApplicationMailProperties mailProperties) {
        this(new ApplicationMailService(sender, mailProperties), properties, mailProperties);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public EmailVerificationMailService(ApplicationMailService mail, EmailVerificationProperties properties,
                                        ApplicationMailProperties mailProperties) {
        this.mail = mail; this.properties = properties; this.mailProperties = mailProperties;
    }
    public Optional<EmailVerificationMailFailure> configurationFailure() {
        if (!properties.mailEnabled()) return Optional.of(EmailVerificationMailFailure.MAIL_DISABLED);
        if (mailProperties.from() == null || mailProperties.from().isBlank()) return Optional.of(EmailVerificationMailFailure.SENDER_NOT_CONFIGURED);
        if (properties.url().getHost().toLowerCase(Locale.ROOT).endsWith(".example"))
            return Optional.of(EmailVerificationMailFailure.URL_NOT_CONFIGURED);
        return Optional.empty();
    }
    @EventListener(ApplicationReadyEvent.class)
    public void checkConfiguration() {
        if (!properties.mailEnabled()) return;
        configurationFailure().ifPresent(reason -> log.warn(
                "Email verification mail configuration unavailable: reason={}. Check EMAIL_VERIFICATION_URL and app.mail.from. For localhost development, use the local profile.", reason));
    }
    public void send(String email, String rawToken) throws MessagingException {
        var unavailable = configurationFailure();
        if (unavailable.isPresent()) throw new EmailVerificationMailFailure.Unavailable(unavailable.get());
        // fragment는 HTTP 요청/Referer에 포함되지 않는다. Host 헤더로 URL을 만들지 않는다.
        long hours = properties.tokenTtl().toHours();
        String validity = hours > 0 ? hours + "시간" : properties.tokenTtl().toMinutes() + "분";
        mail.sendAction(email, "[GuildUp] 이메일 인증을 완료해 주세요.", "GuildUp에 가입해 주셔서 감사합니다.",
                "안전한 계정 이용을 위해\n이메일 인증을 완료해 주세요.", "이메일 인증하기",
                properties.url() + "#token=" + rawToken,
                "인증 링크는 " + validity + " 동안 유효합니다.\n화면에서 가입한 계정으로 로그인한 뒤 인증 완료 버튼을 눌러 주세요.");
    }
}
