package com.guildup.community.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/** GuildUp에서 관리하는 하나의 커뮤니티를 나타내는 JPA 엔티티다. */
@Entity
@Table(name = "communities")
public class Community {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "creation_request_key", unique = true, length = 100, updatable = false)
    private String creationRequestKey;

    @Column(name = "creation_request_hash", length = 64, updatable = false)
    private String creationRequestHash;

    @Column(name = "invite_code", unique = true, length = 36)
    private String inviteCode;

    @Enumerated(EnumType.STRING)
    @ColumnDefault("'ALL_TIME'")
    @Column(name = "ranking_period_type", nullable = false, length = 20)
    private RankingPeriodType rankingPeriodType = RankingPeriodType.ALL_TIME;

    public RankingPeriodType getRankingPeriodType() { return rankingPeriodType; }

    public void changeRankingPeriodType(RankingPeriodType periodType) {
        this.rankingPeriodType = java.util.Objects.requireNonNull(periodType);
    }

    /** JPA가 엔티티를 생성할 때 사용하는 기본 생성자다. */
    protected Community() {
    }

    /** 새 커뮤니티를 이름으로 생성한다. */
    public Community(String name) {
        this.name = name;
        this.inviteCode = java.util.UUID.randomUUID().toString();
    }

    public Community(String name, String creationRequestKey, String creationRequestHash) {
        this(name);
        this.creationRequestKey = creationRequestKey;
        this.creationRequestHash = creationRequestHash;
    }

    public String getCreationRequestHash() { return creationRequestHash; }
    public boolean isFinalizedCreation() { return creationRequestKey != null; }

    public String getInviteCode() { return inviteCode; }

    /** 기존 커뮤니티는 운영자가 초대 코드를 요청했을 때만 발급한다. */
    public String ensureInviteCode() {
        if (inviteCode == null) inviteCode = java.util.UUID.randomUUID().toString();
        return inviteCode;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** DB에 처음 저장되는 시각을 기록한다. */
    @PrePersist
    void initializeCreatedAt() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
