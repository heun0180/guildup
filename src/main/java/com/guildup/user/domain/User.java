package com.guildup.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/** Discord 등 외부 계정과 구분되는 GuildUp 자체 사용자다. */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String nickname;

    /** 선택 프로필 정보. 로그인/가입/게임 계정과는 독립적이다. */
    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "system_role", nullable = false, length = 20)
    private SystemRole systemRole = SystemRole.USER;

    public static final String WITHDRAWN_NAME = "탈퇴한 사용자";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @org.hibernate.annotations.ColumnDefault("'ACTIVE'")
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    /** 인증 변경 전 세션과 재설정 링크를 모든 서버에서 거부하는 영속 버전. */
    @Column(name = "authentication_version", nullable = false)
    @org.hibernate.annotations.ColumnDefault("0")
    private long authenticationVersion;

    public long getAuthenticationVersion() { return authenticationVersion; }
    public void advanceAuthenticationVersion() { authenticationVersion = Math.incrementExact(authenticationVersion); }

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected User() {
    }

    public User(String nickname) {
        this.nickname = nickname;
    }

    public Long getId() {
        return id;
    }

    public String getNickname() {
        return nickname;
    }

    public LocalDate getBirthDate() { return birthDate; }

    public void updateProfile(String nickname, LocalDate birthDate) {
        this.nickname = nickname;
        this.birthDate = birthDate;
    }

    public SystemRole getSystemRole() {
        return systemRole;
    }

    public boolean isSystemAdmin() {
        return isActive() && systemRole == SystemRole.SYSTEM_ADMIN;
    }

    public UserStatus getStatus() { return status; }
    public Instant getWithdrawnAt() { return withdrawnAt; }
    public boolean isActive() { return status == UserStatus.ACTIVE; }

    public void withdraw(Instant at) {
        status = UserStatus.WITHDRAWN;
        withdrawnAt = at;
        nickname = WITHDRAWN_NAME;
        birthDate = null;
        systemRole = SystemRole.USER;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @PrePersist
    void initializeTimestamps() {
        Instant now = Instant.now();
        if (systemRole == null) {
            systemRole = SystemRole.USER;
        }
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void updateTimestamp() {
        updatedAt = Instant.now();
    }
}
