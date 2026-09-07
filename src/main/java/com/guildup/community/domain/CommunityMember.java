package com.guildup.community.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import java.time.Instant;
import java.util.Objects;

/**
 * 커뮤니티에 등록된 실제 클랜원을 나타내는 JPA 엔티티다.
 * 외부 서비스 계정은 CommunityMemberAccount에서 별도로 관리한다.
 */
@Entity
@Table(name = "community_members")
public class CommunityMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Community community;

    @Column(nullable = false)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CommunityMemberStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CommunityMember() {
    }

    /** 클랜원을 닉네임으로 등록한다. */
    public CommunityMember(Community community, String nickname) {
        this.community = community;
        this.nickname = nickname;
        this.status = CommunityMemberStatus.ACTIVE;
    }

    public Long getId() {
        return id;
    }

    public String getNickname() {
        return nickname;
    }

    public Community getCommunity() {
        return community;
    }

    public CommunityMemberStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** 닉네임이 실제로 달라졌을 때만 수정하고 변경 여부를 반환한다. */
    public boolean updateNickname(String nickname) {
        if (Objects.equals(this.nickname, nickname)) {
            return false;
        }

        this.nickname = nickname;
        return true;
    }

    /** Discord 프로필과 현재 클랜원 상태를 기존 행에 반영한다. */
    public void synchronizeDiscordProfile(String discordDisplayName, Instant synchronizedAt) {
        this.nickname = discordDisplayName;
        this.status = CommunityMemberStatus.ACTIVE;
        this.updatedAt = synchronizedAt;
    }

    /** 이미 LEFT인 행은 그대로 두고 ACTIVE 행만 LEFT로 전환한다. */
    public boolean markLeft(Instant synchronizedAt) {
        if (status == CommunityMemberStatus.LEFT) {
            return false;
        }
        this.status = CommunityMemberStatus.LEFT;
        this.updatedAt = synchronizedAt;
        return true;
    }

    @PrePersist
    void initializeTimestamps() {
        Instant now = Instant.now();
        if (status == null) status = CommunityMemberStatus.ACTIVE;
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void updateTimestamp() {
        if (updatedAt == null) updatedAt = Instant.now();
    }
}
