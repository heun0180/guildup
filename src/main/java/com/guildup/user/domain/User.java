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

/** Discord 등 외부 계정과 구분되는 GuildUp 자체 사용자다. */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String nickname;

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
