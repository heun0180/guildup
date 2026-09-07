package com.guildup.community.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
        name = "community_member_activity_matches",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_member_activity_match_snapshot_match",
                columnNames = {"activity_snapshot_id", "match_id"}
        )
)
public class CommunityMemberActivityMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "activity_snapshot_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private CommunityMemberActivitySnapshot activity;

    @Column(name = "match_id", nullable = false)
    private String matchId;

    @Column(name = "played_at", nullable = false)
    private Instant playedAt;

    @Column(name = "game_mode")
    private String gameMode;

    @Column(name = "activity_recognized", nullable = false)
    private boolean activityRecognized;

    @Column(name = "clan_member_count_in_team", nullable = false)
    private int clanMemberCountInTeam;

    @OneToMany(mappedBy = "activityMatch", cascade = jakarta.persistence.CascadeType.ALL, orphanRemoval = true)
    private List<CommunityMemberActivityMatchPlayer> players = new ArrayList<>();

    protected CommunityMemberActivityMatch() {
    }

    public CommunityMemberActivityMatch(
            CommunityMemberActivitySnapshot activity,
            String matchId,
            Instant playedAt,
            String gameMode,
            boolean activityRecognized,
            int clanMemberCountInTeam
    ) {
        this.activity = activity;
        this.matchId = matchId;
        this.playedAt = playedAt;
        this.gameMode = gameMode;
        this.activityRecognized = activityRecognized;
        this.clanMemberCountInTeam = clanMemberCountInTeam;
    }

    public void addPlayer(CommunityMemberActivityMatchPlayer player) { players.add(player); }
    public Long getId() { return id; }
    public String getMatchId() { return matchId; }
    public Instant getPlayedAt() { return playedAt; }
    public String getGameMode() { return gameMode; }
    public boolean isActivityRecognized() { return activityRecognized; }
    public int getClanMemberCountInTeam() { return clanMemberCountInTeam; }
    public List<CommunityMemberActivityMatchPlayer> getPlayers() { return List.copyOf(players); }
}
