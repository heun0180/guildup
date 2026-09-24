package com.guildup.bingo.domain;

import com.guildup.community.domain.CommunityMember;
import com.guildup.community.domain.CommunityUser;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "bingo_participants", uniqueConstraints = @UniqueConstraint(
        name = "uk_bingo_participant_user", columnNames = {"bingo_event_id", "community_user_id"}))
public class BingoParticipant {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "bingo_event_id", nullable = false) private BingoEvent event;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "community_user_id", nullable = false) private CommunityUser communityUser;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "community_member_id") private CommunityMember communityMember;
    @Column(name = "pubg_account_id") private String pubgAccountId;
    @Column(name = "pubg_nickname") private String pubgNickname;
    @Column(name = "joined_at", nullable = false) private Instant joinedAt;
    @Column(name = "eligible_from", nullable = false) private Instant eligibleFrom;
    @Column(name = "line_count", nullable = false) private int lineCount;
    @Column(name = "target_lines_completed_at") private Instant targetLinesCompletedAt;
    @Column(name = "blackout_completed_at") private Instant blackoutCompletedAt;
    @Version private long version;

    protected BingoParticipant() {}
    public BingoParticipant(BingoEvent event, CommunityUser user, CommunityMember member,
                            String accountId, String nickname, Instant joinedAt, Instant eligibleFrom) {
        this.event = event; this.communityUser = user; this.communityMember = member;
        this.pubgAccountId = accountId; this.pubgNickname = nickname;
        this.joinedAt = joinedAt; this.eligibleFrom = eligibleFrom;
    }
    public void updateLines(int count, boolean targetReached, boolean blackout, Instant at) {
        lineCount = count;
        if (targetReached && targetLinesCompletedAt == null) targetLinesCompletedAt = at;
        if (blackout && blackoutCompletedAt == null) blackoutCompletedAt = at;
    }
    public boolean synchronizePubgAccount(String accountId, String nickname) {
        if (Objects.equals(pubgAccountId, accountId) && Objects.equals(pubgNickname, nickname)) return false;
        pubgAccountId = accountId;
        pubgNickname = nickname;
        return true;
    }
    /** TEMPORARY repair hook: 검증된 셀에서 파생된 줄/블랙빙고 상태만 보정한다. */
    public boolean replaceDerivedProgress(int count, Instant targetCompletedAt, Instant blackoutCompletedAt) {
        if (lineCount == count && Objects.equals(this.targetLinesCompletedAt, targetCompletedAt)
                && Objects.equals(this.blackoutCompletedAt, blackoutCompletedAt)) return false;
        lineCount = count;
        targetLinesCompletedAt = targetCompletedAt;
        this.blackoutCompletedAt = blackoutCompletedAt;
        return true;
    }
    public Long getId() { return id; }
    public BingoEvent getEvent() { return event; }
    public CommunityUser getCommunityUser() { return communityUser; }
    public CommunityMember getCommunityMember() { return communityMember; }
    public String getPubgAccountId() { return pubgAccountId; }
    public String getPubgNickname() { return pubgNickname; }
    public Instant getJoinedAt() { return joinedAt; }
    public Instant getEligibleFrom() { return eligibleFrom; }
    public int getLineCount() { return lineCount; }
    public Instant getTargetLinesCompletedAt() { return targetLinesCompletedAt; }
    public Instant getBlackoutCompletedAt() { return blackoutCompletedAt; }
}
