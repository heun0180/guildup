package com.guildup.community.domain;

import com.guildup.community.activity.ClanActivityStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "community_member_activity_snapshots",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_member_activity_snapshot_game_member",
                columnNames = {"community_game_id", "community_member_id"}
        )
)
public class CommunityMemberActivitySnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_game_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private CommunityGame communityGame;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_member_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private CommunityMember communityMember;

    @Column(name = "pubg_account_id")
    private String pubgAccountId;

    @Column(name = "game_nickname")
    private String gameNickname;

    @Enumerated(EnumType.STRING)
    @Column(name = "activity_status", nullable = false)
    private ClanActivityStatus activityStatus;

    @Column(name = "last_pubg_match_at")
    private Instant lastPubgMatchAt;

    @Column(name = "last_clan_activity_at")
    private Instant lastClanActivityAt;

    @Column(name = "synchronized_at", nullable = false)
    private Instant synchronizedAt;

    @OneToMany(mappedBy = "activity", cascade = jakarta.persistence.CascadeType.ALL, orphanRemoval = true)
    private List<CommunityMemberActivityMatch> matches = new ArrayList<>();

    protected CommunityMemberActivitySnapshot() {
    }

    public CommunityMemberActivitySnapshot(
            CommunityGame communityGame,
            CommunityMember communityMember,
            String pubgAccountId,
            String gameNickname,
            ClanActivityStatus activityStatus,
            Instant lastPubgMatchAt,
            Instant lastClanActivityAt,
            Instant synchronizedAt
    ) {
        this.communityGame = communityGame;
        this.communityMember = communityMember;
        this.pubgAccountId = pubgAccountId;
        this.gameNickname = gameNickname;
        this.activityStatus = activityStatus;
        this.lastPubgMatchAt = lastPubgMatchAt;
        this.lastClanActivityAt = lastClanActivityAt;
        this.synchronizedAt = synchronizedAt;
    }

    public void addMatch(CommunityMemberActivityMatch match) {
        matches.add(match);
    }

    public Long getId() { return id; }
    public CommunityMember getCommunityMember() { return communityMember; }
    public String getPubgAccountId() { return pubgAccountId; }
    public String getGameNickname() { return gameNickname; }
    public ClanActivityStatus getActivityStatus() { return activityStatus; }
    public Instant getLastPubgMatchAt() { return lastPubgMatchAt; }
    public Instant getLastClanActivityAt() { return lastClanActivityAt; }
    public Instant getSynchronizedAt() { return synchronizedAt; }
    public List<CommunityMemberActivityMatch> getMatches() { return List.copyOf(matches); }
}
