package com.guildup.community.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

/** 점수 변동 사유를 추적하는 변경 불가 원장 행이다. */
@Entity
@Table(
        name = "community_score_history",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_community_score_history_reference",
                columnNames = {"community_member_id", "score_type", "reference_type", "reference_id"}
        ),
        indexes = @Index(
                name = "idx_community_score_history_member_created",
                columnList = "community_member_id, created_at, id"
        )
)
public class CommunityScoreHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Community community;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_member_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private CommunityMember communityMember;

    @Column(name = "score_change", nullable = false)
    private int scoreChange;

    @Enumerated(EnumType.STRING)
    @Column(name = "score_type", nullable = false, length = 50)
    private CommunityScoreType scoreType;

    @Column(nullable = false, length = 200)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", nullable = false, length = 50)
    private CommunityScoreReferenceType referenceType;

    @Column(name = "reference_id", nullable = false)
    private Long referenceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CommunityScoreHistory() {}

    public CommunityScoreHistory(CommunityMember member, int scoreChange, CommunityScoreType scoreType,
                                 String description, CommunityScoreReferenceType referenceType,
                                 Long referenceId, Instant createdAt) {
        this.communityMember = member;
        this.community = member.getCommunity();
        this.scoreChange = scoreChange;
        this.scoreType = scoreType;
        this.description = description;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Community getCommunity() { return community; }
    public CommunityMember getCommunityMember() { return communityMember; }
    public int getScoreChange() { return scoreChange; }
    public CommunityScoreType getScoreType() { return scoreType; }
    public String getDescription() { return description; }
    public CommunityScoreReferenceType getReferenceType() { return referenceType; }
    public Long getReferenceId() { return referenceId; }
    public Instant getCreatedAt() { return createdAt; }
}
