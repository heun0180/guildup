package com.guildup.user.reset;

import com.guildup.user.domain.User;
import com.guildup.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Clock;
import java.util.Optional;

@Service
public class PasswordResetDeliveryTransactions {
    private final UserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final Clock clock;
    public PasswordResetDeliveryTransactions(UserRepository users, PasswordResetTokenRepository tokens, Clock clock) {
        this.users = users; this.tokens = tokens; this.clock = clock;
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Recipient> begin(Long id) {
        var owner = tokens.ownerById(id);
        if (owner.isEmpty() || users.findForUpdate(owner.get()).filter(User::isActive).isEmpty()) return Optional.empty();
        var token = tokens.findById(id).orElse(null);
        if (token == null || token.getDelivery() != PasswordResetToken.Delivery.QUEUED) return Optional.empty();
        if (token.getUsedAt() != null || token.getInvalidatedAt() != null || !token.getExpiresAt().isAfter(clock.instant())
                || token.getAuthenticationVersion() != token.getCredential().getUser().getAuthenticationVersion()) {
            token.delivery(PasswordResetToken.Delivery.FAILED); return Optional.empty();
        }
        token.delivery(PasswordResetToken.Delivery.SENDING);
        return Optional.of(new Recipient(owner.get(), token.getCredential().getEmail()));
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(Long id, boolean sent) {
        var owner = tokens.ownerById(id);
        if (owner.isEmpty()) return;
        users.findForUpdate(owner.get());
        tokens.findById(id).ifPresent(token -> {
            token.delivery(sent ? PasswordResetToken.Delivery.SENT : PasswordResetToken.Delivery.FAILED);
            if (!sent && token.getUsedAt() == null) token.invalidate(clock.instant());
        });
    }
    public record Recipient(Long userId, String email) {
        @Override public String toString() { return "PasswordResetRecipient"; }
    }
}
