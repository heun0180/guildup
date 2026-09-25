package com.guildup.community.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

/** 랭킹을 빠르게 읽기 위한 커뮤니티 멤버별 현재 총점이다. */
@Entity
@Table(
        name = "community_member_scores",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_community_member_score_member",
                columnNames = "community_member_id"
        ),
        indexes = @Index(
                name = "idx_community_member_score_ranking",
                columnList = "community_id, total_score, community_member_id"
        )
)
public class CommunityMemberScore {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Community community;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_member_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private CommunityMember communityMember;

    @Column(name = "total_score", nullable = false)
    private int totalScore;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CommunityMemberScore() {}

    public CommunityMemberScore(CommunityMember member, Instant updatedAt) {
        this.communityMember = member;
        this.community = member.getCommunity();
        this.totalScore = 0;
        this.updatedAt = updatedAt;
    }

    public void add(int score, Instant updatedAt) {
        this.totalScore = Math.addExact(this.totalScore, score);
        this.updatedAt = updatedAt;
    }

    public void remove(int score, Instant updatedAt) {
        if (score < 0 || totalScore < score) throw new IllegalStateException("차감할 커뮤니티 점수가 올바르지 않습니다.");
        totalScore = Math.subtractExact(totalScore, score);
        this.updatedAt = updatedAt;
    }

    public Long getId() { return id; }
    public Community getCommunity() { return community; }
    public CommunityMember getCommunityMember() { return communityMember; }
    public int getTotalScore() { return totalScore; }
    public Instant getUpdatedAt() { return updatedAt; }
}
