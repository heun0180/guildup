package com.guildup.user.verification;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.*;

/** 예외 메시지·주소·링크 대신 고정된 원인만 운영 로그와 모니터링에 전달한다. */
public enum EmailVerificationMailFailure {
    MAIL_DISABLED, SENDER_NOT_CONFIGURED, URL_NOT_CONFIGURED,
    SMTP_AUTHENTICATION_FAILED, SMTP_TIMEOUT, SMTP_CONNECTION_FAILED,
    SMTP_SEND_FAILED, MESSAGE_CREATION_FAILED, DELIVERY_STORAGE_FAILED, QUEUE_FULL;

    public static EmailVerificationMailFailure classify(Throwable failure) {
        var pending = new ArrayDeque<Throwable>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        pending.add(failure);
        EmailVerificationMailFailure result = failure instanceof MessagingException ? MESSAGE_CREATION_FAILED : SMTP_SEND_FAILED;
        // 중첩 예외 및 Jakarta Mail의 nextException을 확인하되 예외 문자열은 읽지 않는다.
        while (!pending.isEmpty() && seen.size() < 32) {
            var current = pending.removeFirst();
            if (!seen.add(current)) continue;
            if (current instanceof Unavailable unavailable) return unavailable.reason();
            if (current instanceof MailAuthenticationException || current instanceof AuthenticationFailedException)
                return SMTP_AUTHENTICATION_FAILED;
            if (current instanceof SocketTimeoutException) result = SMTP_TIMEOUT;
            else if ((current instanceof ConnectException || current instanceof UnknownHostException) && result != SMTP_TIMEOUT)
                result = SMTP_CONNECTION_FAILED;
            if (current.getCause() != null) pending.add(current.getCause());
            if (current instanceof MessagingException messaging && messaging.getNextException() != null)
                pending.add(messaging.getNextException());
            if (current instanceof MailSendException send)
                Collections.addAll(pending, send.getMessageExceptions());
        }
        return result;
    }

    public static final class Unavailable extends IllegalStateException {
        private final EmailVerificationMailFailure reason;
        public Unavailable(EmailVerificationMailFailure reason) { super(reason.name()); this.reason = reason; }
        public EmailVerificationMailFailure reason() { return reason; }
    }
}
