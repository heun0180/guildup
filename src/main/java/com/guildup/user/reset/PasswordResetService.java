package com.guildup.user.reset;

import com.guildup.monitoring.domain.MonitoringEventCode;
import com.guildup.user.auth.exception.AuthException;
import com.guildup.user.auth.service.AuthSessionService;
import com.guildup.user.auth.token.AuthTokenPurpose;
import com.guildup.user.domain.*;
import com.guildup.user.repository.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import java.time.*;

@Service
public class PasswordResetService {
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
    private final UserRepository users;
    private final UserCredentialRepository credentials;
    private final PasswordResetTokenRepository tokens;
    private final PasswordResetMailQuotaRepository quota;
    private final PasswordResetProperties properties;
    private final ApplicationEventPublisher publisher;
    private final PasswordResetEvents events;
    private final AuthSessionService sessions;
    private final Clock clock;
    public PasswordResetService(UserRepository users, UserCredentialRepository credentials,
            PasswordResetTokenRepository tokens, PasswordResetMailQuotaRepository quota, PasswordResetProperties properties,
            ApplicationEventPublisher publisher, PasswordResetEvents events, AuthSessionService sessions, Clock clock) {
        this.users = users; this.credentials = credentials; this.tokens = tokens; this.quota = quota;
        this.properties = properties; this.publisher = publisher; this.events = events; this.sessions = sessions; this.clock = clock;
    }
    @Transactional
    public void initializeQuota() {
        if (!quota.existsById(1L)) quota.saveAndFlush(new PasswordResetMailQuota(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)));
    }
    /** Account lookup and limits run only in the bounded worker, never on the public response path. */
    @Transactional
    public void issue(String email) {
        if (!properties.mailEnabled()) return;
        var found = credentials.findByEmail(email);
        if (found.isEmpty()) return; // Includes Discord-only accounts; never create credentials.
        Long userId = found.get().getUser().getId();
        if (users.findForUpdate(userId).filter(User::isActive).isEmpty()) return;
        var credential = credentials.findByUserId(userId).filter(value -> value.getEmail().equals(email)).orElse(null);
        if (credential == null) return;
        Instant now = clock.instant();
        var recent = tokens.findByCredentialIdAndCreatedAtGreaterThanOrderByCreatedAtAscIdAsc(credential.getId(), now.minus(Duration.ofDays(1)));
        if ((!recent.isEmpty() && recent.getLast().getCreatedAt().plus(properties.requestInterval()).isAfter(now))
                || recent.stream().filter(t -> t.getCreatedAt().isAfter(now.minus(Duration.ofHours(1)))).count() >= properties.accountHourlyLimit()
                || recent.size() >= properties.accountDailyLimit()) {
            events.record(MonitoringEventCode.PASSWORD_RESET_RATE_LIMITED, userId); return;
        }
        var budget = quota.locked().orElseThrow(() -> new IllegalStateException("Password reset mail budget unavailable"));
        if (!budget.reserve(LocalDate.ofInstant(now, ZoneOffset.UTC), properties)) {
            events.record(MonitoringEventCode.PASSWORD_RESET_RATE_LIMITED, null); return;
        }
        tokens.findByCredentialIdAndUsedAtIsNullAndInvalidatedAtIsNull(credential.getId()).forEach(token -> token.invalidate(now));
        tokens.flush(); // partial UNIQUE must see revocations before an IDENTITY insert.
        String raw = PasswordResetTokens.generate();
        var token = tokens.saveAndFlush(new PasswordResetToken(credential, PasswordResetTokens.hash(raw), now, now.plus(properties.tokenTtl())));
        publisher.publishEvent(new MailRequested(token.getId(), raw));
    }
    /** Safe to call for a page preview. Neither this nor any GET consumes the token. */
    @Transactional(readOnly = true)
    public void validate(String raw) {
        check(load(raw));
    }
    /** Always identify the target by the token, never by the browser's logged-in account. */
    @Transactional
    public void confirmEncoded(String raw, String encodedPassword) {
        if (!PasswordResetTokens.validFormat(raw)) throw rejected(MonitoringEventCode.PASSWORD_RESET_INVALID, null);
        String hash = PasswordResetTokens.hash(raw);
        Long owner = tokens.ownerByHash(hash).orElseThrow(() -> rejected(MonitoringEventCode.PASSWORD_RESET_INVALID, null));
        users.findForUpdate(owner).filter(User::isActive).orElseThrow(() -> rejected(MonitoringEventCode.PASSWORD_RESET_INVALID, null));
        var token = tokens.findByTokenHash(hash).orElseThrow(() -> rejected(MonitoringEventCode.PASSWORD_RESET_INVALID, null));
        // Controller preflight validation can populate the OSIV cache before another request replaces
        // this link or verifies the email. Reload both under the user lock before reading or writing.
        entityManager.refresh(token);
        entityManager.refresh(token.getCredential());
        check(token);
        var credential = credentials.findByUserId(owner).orElseThrow(() -> rejected(MonitoringEventCode.PASSWORD_RESET_INVALID, null));
        if (!credential.getId().equals(token.getCredential().getId())) throw rejected(MonitoringEventCode.PASSWORD_RESET_INVALID, null);
        Instant now = clock.instant();
        token.use(now);
        credential.changePasswordHash(encodedPassword);
        tokens.findByCredentialIdAndUsedAtIsNullAndInvalidatedAtIsNull(credential.getId()).forEach(other -> other.invalidate(now));
        long version = credential.getUser().getAuthenticationVersion();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                sessions.revokeBeforeVersion(owner, version);
                events.record(MonitoringEventCode.PASSWORD_RESET_COMPLETED, owner);
            }
        });
        // email_verified, users.id, Discord links and all community records remain unchanged.
    }
    private PasswordResetToken load(String raw) {
        if (!PasswordResetTokens.validFormat(raw)) throw rejected(MonitoringEventCode.PASSWORD_RESET_INVALID, null);
        return tokens.findByTokenHash(PasswordResetTokens.hash(raw)).orElseThrow(() -> rejected(MonitoringEventCode.PASSWORD_RESET_INVALID, null));
    }
    private void check(PasswordResetToken token) {
        Long userId = token.getCredential().getUser().getId();
        if (token.getPurpose() != AuthTokenPurpose.PASSWORD_RESET || !token.getCredential().getUser().isActive())
            throw rejected(MonitoringEventCode.PASSWORD_RESET_INVALID, null);
        if (token.getUsedAt() != null) throw rejected(MonitoringEventCode.PASSWORD_RESET_REUSED, userId);
        if (token.getInvalidatedAt() != null || token.getAuthenticationVersion() != token.getCredential().getUser().getAuthenticationVersion())
            throw rejected(MonitoringEventCode.PASSWORD_RESET_INVALID, userId);
        if (!token.getExpiresAt().isAfter(clock.instant())) throw rejected(MonitoringEventCode.PASSWORD_RESET_EXPIRED, userId);
    }
    private AuthException rejected(MonitoringEventCode code, Long userId) {
        events.record(code, userId);
        String message = code == MonitoringEventCode.PASSWORD_RESET_EXPIRED
                ? "재설정 링크가 만료되었습니다. 비밀번호 찾기에서 새 링크를 요청해 주세요."
                : "재설정 링크를 사용할 수 없습니다. 비밀번호 찾기에서 새 링크를 요청해 주세요.";
        return new AuthException(HttpStatus.BAD_REQUEST, code.name(), message);
    }
    public record MailRequested(Long tokenId, String rawToken) {
        @Override public String toString() { return "PasswordResetMailRequested"; }
    }
}
