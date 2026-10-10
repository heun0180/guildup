package com.guildup.user.verification;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import java.util.concurrent.Executor;

@Component
public class EmailVerificationMailListener {
    private final Executor executor;
    private final EmailVerificationDeliveryTransactions delivery;
    private final EmailVerificationMailService mail;
    private final EmailVerificationEvents events;
    public EmailVerificationMailListener(@Qualifier("emailVerificationExecutor") Executor executor,
            EmailVerificationDeliveryTransactions delivery, EmailVerificationMailService mail, EmailVerificationEvents events) {
        this.executor = executor; this.delivery = delivery; this.mail = mail; this.events = events;
    }
    @TransactionalEventListener
    public void committed(EmailVerificationService.MailRequested request) {
        try { executor.execute(() -> deliver(request)); }
        catch (RuntimeException rejected) { failed(request.tokenId(), null, EmailVerificationMailFailure.QUEUE_FULL); }
    }
    private void deliver(EmailVerificationService.MailRequested request) {
        Long userId = null;
        EmailVerificationMailFailure reason = EmailVerificationMailFailure.DELIVERY_STORAGE_FAILED;
        try {
            var recipient = delivery.begin(request.tokenId());
            if (recipient.isEmpty()) return;
            userId = recipient.get().userId();
            reason = null;
            mail.send(recipient.get().email(), request.rawToken());
            reason = EmailVerificationMailFailure.DELIVERY_STORAGE_FAILED;
            delivery.finish(request.tokenId(), true);
        } catch (Exception failure) {
            failed(request.tokenId(), userId, reason == null ? EmailVerificationMailFailure.classify(failure) : reason);
        }
    }
    private void failed(Long tokenId, Long userId, EmailVerificationMailFailure reason) {
        // SMTP 예외 메시지/스택은 수신자·링크를 포함할 수 있으므로 기록하지 않는다.
        try { delivery.finish(tokenId, false); }
        catch (RuntimeException ignored) { /* 상태 조회의 시간 초과 및 재발송으로 복구할 수 있다. */ }
        events.mailFailed(userId, reason);
    }
}
