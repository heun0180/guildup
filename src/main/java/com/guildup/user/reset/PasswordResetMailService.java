package com.guildup.user.reset;

import com.guildup.mail.ApplicationMailService;
import com.guildup.mail.config.ApplicationMailProperties;
import com.guildup.user.verification.EmailVerificationMailFailure;
import jakarta.mail.MessagingException;
import org.springframework.stereotype.Service;

@Service
public class PasswordResetMailService {
    private final ApplicationMailService mail;
    private final ApplicationMailProperties sender;
    private final PasswordResetProperties properties;
    public PasswordResetMailService(ApplicationMailService mail, ApplicationMailProperties sender, PasswordResetProperties properties) {
        this.mail = mail; this.sender = sender; this.properties = properties;
    }
    public void send(String email, String rawToken) throws MessagingException {
        if (!properties.mailEnabled()) throw new EmailVerificationMailFailure.Unavailable(EmailVerificationMailFailure.MAIL_DISABLED);
        if (sender.from() == null || sender.from().isBlank()) throw new EmailVerificationMailFailure.Unavailable(EmailVerificationMailFailure.SENDER_NOT_CONFIGURED);
        if (properties.url().getHost().endsWith(".example")) throw new EmailVerificationMailFailure.Unavailable(EmailVerificationMailFailure.URL_NOT_CONFIGURED);
        mail.sendAction(email, "[GuildUp] 비밀번호 재설정 안내", "GuildUp 비밀번호 재설정 요청이 접수되었습니다.",
                "아래 버튼을 클릭하여 새로운 비밀번호를 설정해 주세요.", "비밀번호 재설정하기",
                properties.url() + "#token=" + rawToken,
                "해당 링크는 " + properties.tokenTtl().toMinutes() + "분 동안 유효하며\n한 번 사용하면 다시 사용할 수 없습니다.");
    }
}
