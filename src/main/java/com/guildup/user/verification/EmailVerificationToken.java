package com.guildup.user.verification;

import com.guildup.user.domain.UserCredential;
import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import java.time.Instant;

/** 이메일 소유 확인 전용. 원문/이메일/인증 URL은 저장하지 않는다. */
@Entity
@Table(name = "email_verification_tokens", indexes = {
        @Index(name = "idx_email_verification_credential_created", columnList = "credential_id,created_at"),
        @Index(name = "idx_email_verification_expires", columnList = "expires_at")})
public class EmailVerificationToken {
    public enum Delivery { QUEUED, SENDING, SENT, FAILED }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "credential_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private UserCredential credential;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32)
    @org.hibernate.annotations.ColumnDefault("'EMAIL_VERIFICATION'")
    private com.guildup.user.auth.token.AuthTokenPurpose purpose = com.guildup.user.auth.token.AuthTokenPurpose.EMAIL_VERIFICATION;
    @Column(name = "token_hash", nullable = false, unique = true, length = 64) private String tokenHash;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "used_at") private Instant usedAt;
    @Column(name = "invalidated_at") private Instant invalidatedAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private Delivery delivery;
    protected EmailVerificationToken() {}
    public EmailVerificationToken(UserCredential credential, String hash, Instant now, Instant expiresAt) {
        this.credential = credential; tokenHash = hash; createdAt = now;
        this.expiresAt = expiresAt; delivery = Delivery.QUEUED;
    }
    public Long getId() { return id; }
    public UserCredential getCredential() { return credential; }
    public com.guildup.user.auth.token.AuthTokenPurpose getPurpose() { return purpose; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getUsedAt() { return usedAt; }
    public Instant getInvalidatedAt() { return invalidatedAt; }
    public Delivery getDelivery() { return delivery; }
    public void use(Instant now) { usedAt = now; }
    public void invalidate(Instant now) { invalidatedAt = now; }
    public void delivery(Delivery value) { delivery = value; }
}
