package com.guildup.user.verification;

import com.guildup.user.domain.User;
import com.guildup.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Clock;
import java.util.Optional;

@Service
public class EmailVerificationDeliveryTransactions {
    private final UserRepository users;
    private final EmailVerificationTokenRepository tokens;
    private final Clock clock;
    public EmailVerificationDeliveryTransactions(UserRepository users, EmailVerificationTokenRepository tokens, Clock clock) {
        this.users = users; this.tokens = tokens; this.clock = clock;
    }
    /** SMTP 중에는 DB 트랜잭션/사용자 잠금을 유지하지 않는다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Recipient> begin(Long id) {
        var owner = tokens.ownerById(id);
        if (owner.isEmpty() || users.findForUpdate(owner.get()).filter(User::isActive).isEmpty()) return Optional.empty();
        var token = tokens.findById(id).orElse(null);
        if (token == null || token.getDelivery() != EmailVerificationToken.Delivery.QUEUED) return Optional.empty();
        if (token.getUsedAt() != null || token.getInvalidatedAt() != null
                || !token.getExpiresAt().isAfter(clock.instant()) || token.getCredential().isEmailVerified()) {
            token.delivery(EmailVerificationToken.Delivery.FAILED);
            return Optional.empty();
        }
        token.delivery(EmailVerificationToken.Delivery.SENDING);
        return Optional.of(new Recipient(owner.get(), token.getCredential().getEmail()));
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(Long id, boolean sent) {
        var owner = tokens.ownerById(id);
        if (owner.isEmpty()) return;
        users.findForUpdate(owner.get());
        tokens.findById(id).ifPresent(token -> token.delivery(sent ? EmailVerificationToken.Delivery.SENT : EmailVerificationToken.Delivery.FAILED));
    }
    public record Recipient(Long userId, String email) {
        @Override public String toString() { return "EmailVerificationRecipient"; }
    }
}
