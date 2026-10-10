package com.guildup.user.reset;

import com.guildup.user.verification.EmailVerificationMailFailure;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import java.util.concurrent.Executor;

/** Mirrors verification delivery: commit -> bounded queue -> short DB claim -> SMTP without transaction -> status. */
@Component
public class PasswordResetMailListener {
    private final Executor executor;
    private final PasswordResetDeliveryTransactions delivery;
    private final PasswordResetMailService mail;
    private final PasswordResetEvents events;
    public PasswordResetMailListener(@Qualifier("passwordResetMailExecutor") Executor executor,
            PasswordResetDeliveryTransactions delivery, PasswordResetMailService mail, PasswordResetEvents events) {
        this.executor = executor; this.delivery = delivery; this.mail = mail; this.events = events;
    }
    @TransactionalEventListener
    public void committed(PasswordResetService.MailRequested request) {
        try { executor.execute(() -> deliver(request)); }
        catch (RuntimeException full) { failed(request.tokenId(), null, EmailVerificationMailFailure.QUEUE_FULL); }
    }
    private void deliver(PasswordResetService.MailRequested request) {
        Long userId = null;
        var reason = EmailVerificationMailFailure.DELIVERY_STORAGE_FAILED;
        try {
            var recipient = delivery.begin(request.tokenId());
            if (recipient.isEmpty()) return;
            userId = recipient.get().userId(); reason = null;
            mail.send(recipient.get().email(), request.rawToken());
            reason = EmailVerificationMailFailure.DELIVERY_STORAGE_FAILED;
            delivery.finish(request.tokenId(), true);
        } catch (Exception failure) {
            failed(request.tokenId(), userId, reason == null ? EmailVerificationMailFailure.classify(failure) : reason);
        }
    }
    private void failed(Long tokenId, Long userId, EmailVerificationMailFailure reason) {
        try { delivery.finish(tokenId, false); }
        catch (RuntimeException unavailable) { /* No automatic retry; a new request after cooldown recovers. */ }
        events.mailFailed(userId, reason); // Never forward SMTP messages, exception stacks, email or links.
    }
}
