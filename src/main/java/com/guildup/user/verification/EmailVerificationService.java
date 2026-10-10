package com.guildup.user.verification;

import com.guildup.monitoring.domain.MonitoringEventCode;
import com.guildup.user.auth.exception.*;
import com.guildup.user.domain.*;
import com.guildup.user.repository.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import java.time.*;
import java.util.*;

@Service
public class EmailVerificationService {
    private final UserRepository users;
    private final UserCredentialRepository credentials;
    private final EmailVerificationTokenRepository tokens;
    private final EmailVerificationProperties properties;
    private final ApplicationEventPublisher publisher;
    private final EmailVerificationEvents events;
    private final Clock clock;
    private final EmailVerificationAccessPolicy policy;
    public EmailVerificationService(UserRepository users, UserCredentialRepository credentials,
            EmailVerificationTokenRepository tokens, EmailVerificationProperties properties,
            ApplicationEventPublisher publisher, EmailVerificationEvents events, Clock clock, EmailVerificationAccessPolicy policy) {
        this.users = users; this.credentials = credentials; this.tokens = tokens; this.properties = properties;
        this.publisher = publisher; this.events = events; this.clock = clock;
        this.policy = policy;
    }

    /** 가입/인증정보 저장과 토큰 생성을 하나의 트랜잭션에서 처리한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void issueInitial(UserCredential credential) { issue(credential); }

    @Transactional
    public Status resend(Long userId) {
        activeLocked(userId);
        var credential = credentials.findByUserId(userId);
        if (credential.isEmpty() || credential.get().isEmailVerified()) return statusFor(credential);
        long retry = retryAfter(credential.get().getId());
        if (retry > 0) {
            events.record(MonitoringEventCode.EMAIL_VERIFICATION_RATE_LIMITED, userId);
            throw new EmailVerificationRateLimitedException(retry);
        }
        issue(credential.get());
        return statusFor(credential);
    }

    @Transactional(readOnly = true)
    public Status status(Long userId) {
        users.findById(userId).filter(User::isActive).orElseThrow(EmailVerificationService::loginRequired);
        return statusFor(credentials.findByUserId(userId));
    }

    @Transactional
    public void confirm(Long userId, String raw) {
        // 형식 검증 후 해시만 DB에 전달한다. 사용자 잠금은 재발송/탈퇴/Discord 연결과 동일한 순서다.
        if (!EmailVerificationTokens.validFormat(raw)) throw rejected("EMAIL_VERIFICATION_INVALID", userId);
        String hash = EmailVerificationTokens.hash(raw);
        Long owner = tokens.ownerByHash(hash).orElseThrow(() -> rejected("EMAIL_VERIFICATION_INVALID", userId));
        if (!owner.equals(userId)) throw rejected("EMAIL_VERIFICATION_INVALID", userId);
        activeLocked(userId);
        var credential = credentials.findByUserId(userId).orElseThrow(() -> rejected("EMAIL_VERIFICATION_INVALID", userId));
        var token = tokens.findByTokenHash(hash).orElseThrow(() -> rejected("EMAIL_VERIFICATION_INVALID", userId));
        if (token.getPurpose() != com.guildup.user.auth.token.AuthTokenPurpose.EMAIL_VERIFICATION) throw rejected("EMAIL_VERIFICATION_INVALID", userId);
        if (!token.getCredential().getId().equals(credential.getId())) throw rejected("EMAIL_VERIFICATION_INVALID", userId);
        if (token.getUsedAt() != null) throw rejected("EMAIL_VERIFICATION_USED", userId);
        if (token.getInvalidatedAt() != null) throw rejected("EMAIL_VERIFICATION_REPLACED", userId);
        Instant now = clock.instant();
        if (!token.getExpiresAt().isAfter(now)) throw rejected("EMAIL_VERIFICATION_EXPIRED", userId);
        if (credential.isEmailVerified()) throw rejected("EMAIL_VERIFICATION_USED", userId);
        token.use(now);
        credential.verifyEmail();
        tokens.findByCredentialIdAndUsedAtIsNullAndInvalidatedAtIsNull(credential.getId())
                .stream().filter(other -> !other.getId().equals(token.getId())).forEach(other -> other.invalidate(now));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { events.record(MonitoringEventCode.EMAIL_VERIFICATION_COMPLETED, userId); }
        });
    }

    private void issue(UserCredential credential) {
        Instant now = clock.instant();
        tokens.findByCredentialIdAndUsedAtIsNullAndInvalidatedAtIsNull(credential.getId()).forEach(token -> token.invalidate(now));
        // IDENTITY inserts can precede pending updates. Flush revocations before the partial UNIQUE index sees a new token.
        tokens.flush();
        String raw = EmailVerificationTokens.generate();
        var token = tokens.saveAndFlush(new EmailVerificationToken(credential, EmailVerificationTokens.hash(raw),
                now, now.plus(properties.tokenTtl())));
        publisher.publishEvent(new MailRequested(token.getId(), raw));
    }

    private Status statusFor(Optional<UserCredential> credential) {
        if (credential.isEmpty()) return new Status(false, false, "NONE", 0, false);
        var value = credential.get();
        if (value.isEmailVerified()) return new Status(true, true, "VERIFIED", 0, false);
        var latest = tokens.findFirstByCredentialIdOrderByCreatedAtDescIdDesc(value.getId());
        String delivery = latest.map(token -> {
            if ((token.getDelivery() == EmailVerificationToken.Delivery.QUEUED || token.getDelivery() == EmailVerificationToken.Delivery.SENDING)
                    && (!token.getExpiresAt().isAfter(clock.instant())
                        || !token.getCreatedAt().plus(properties.deliveryTimeout()).isAfter(clock.instant()))) return "FAILED";
            return token.getDelivery().name();
        }).orElse("NOT_REQUESTED");
        return new Status(true, false, delivery, retryAfter(value.getId()), policy.restricted(value.getUser().getId()));
    }

    private long retryAfter(Long credentialId) {
        Instant now = clock.instant();
        var recent = tokens.findByCredentialIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAscIdAsc(credentialId, now.minus(Duration.ofDays(1)));
        if (recent.isEmpty()) return 0;
        Instant next = recent.getLast().getCreatedAt().plus(properties.resendInterval());
        var hourly = recent.stream().filter(token -> token.getCreatedAt().isAfter(now.minus(Duration.ofHours(1)))).toList();
        if (hourly.size() >= properties.accountHourlyLimit())
            next = later(next, hourly.get(hourly.size() - properties.accountHourlyLimit()).getCreatedAt().plus(Duration.ofHours(1)));
        if (recent.size() >= properties.accountDailyLimit())
            next = later(next, recent.get(recent.size() - properties.accountDailyLimit()).getCreatedAt().plus(Duration.ofDays(1)));
        return Math.max(0, (long) Math.ceil(Duration.between(now, next).toMillis() / 1000.0));
    }
    private Instant later(Instant a, Instant b) { return a.isAfter(b) ? a : b; }
    private void activeLocked(Long userId) { users.findForUpdate(userId).filter(User::isActive).orElseThrow(EmailVerificationService::loginRequired); }
    private static AuthException loginRequired() { return new AuthException(HttpStatus.UNAUTHORIZED, "LOGIN_REQUIRED", "로그인이 필요합니다."); }
    private AuthException rejected(String code, Long userId) {
        events.record(code.equals("EMAIL_VERIFICATION_EXPIRED") ? MonitoringEventCode.EMAIL_VERIFICATION_EXPIRED : MonitoringEventCode.EMAIL_VERIFICATION_INVALID, userId);
        String message = switch (code) {
            case "EMAIL_VERIFICATION_EXPIRED" -> "인증 링크가 만료되었습니다. 인증 메일을 다시 요청해 주세요.";
            case "EMAIL_VERIFICATION_USED" -> "이미 사용한 인증 링크입니다. 계정 설정에서 인증 상태를 확인해 주세요.";
            case "EMAIL_VERIFICATION_REPLACED" -> "새 인증 링크가 발급되었습니다. 가장 최근에 받은 메일을 확인해 주세요.";
            default -> "인증 링크를 확인할 수 없습니다. 가입한 계정으로 로그인한 뒤 최근 메일을 다시 열거나 인증 메일을 요청해 주세요.";
        };
        return new AuthException(HttpStatus.BAD_REQUEST, code, message);
    }
    public record Status(boolean hasEmailCredential, boolean emailVerified, String deliveryStatus, long retryAfterSeconds, boolean newUserPolicyApplies) {}
    /** 비밀이 포함된 이벤트의 자동 toString을 금지한다. 원문은 발송 큐 메모리에만 잠시 존재한다. */
    public record MailRequested(Long tokenId, String rawToken) {
        @Override public String toString() { return "EmailVerificationMailRequested"; }
    }
}
