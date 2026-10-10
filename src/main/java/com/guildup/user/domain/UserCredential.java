package com.guildup.user.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

/** 로그인 수단일 뿐이며 GuildUp의 사용자 식별자는 항상 users.id다. */
@Entity
@org.hibernate.annotations.Check(name = "ck_user_credential_email_normalized", constraints = "email = lower(trim(email))")
@Table(name = "user_credentials", uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_credential_user", columnNames = "user_id"),
        @UniqueConstraint(name = "uk_user_credential_email", columnNames = "email")
})
public class UserCredential {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_user_credential_user"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    /** 기존 계정은 false를 유지한다. 이번 기능 이후 이메일 신규 가입에만 설정한다. */
    @Column(name = "verification_required", nullable = false)
    @org.hibernate.annotations.ColumnDefault("false")
    private boolean verificationRequired;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserCredential() {}

    public UserCredential(User user, String normalizedEmail, String passwordHash) {
        this.user = user;
        this.email = normalizedEmail;
        this.passwordHash = passwordHash;
        this.emailVerified = false;
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    /** 호출자는 users 행을 먼저 잠근다. 모든 비밀번호 변경은 인증 버전도 회전한다. */
    public void changePasswordHash(String encoded) {
        passwordHash = encoded;
        user.advanceAuthenticationVersion();
    }
    public boolean isEmailVerified() { return emailVerified; }
    public boolean isVerificationRequired() { return verificationRequired; }
    public void requireEmailVerification() { verificationRequired = true; }
    public void verifyEmail() { emailVerified = true; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    @PrePersist
    void initializeTimestamps() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void updateTimestamp() { updatedAt = Instant.now(); }
}
