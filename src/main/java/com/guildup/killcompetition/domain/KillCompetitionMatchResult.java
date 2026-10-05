package com.guildup.killcompetition.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import java.time.Instant;

@Entity
@Table(name = "pubg_kill_competition_match_results", uniqueConstraints = @UniqueConstraint(
        name = "uk_pubg_kill_competition_match_participant", columnNames = {"competition_id", "match_id", "participant_id"}
), indexes = @Index(name = "idx_pubg_kill_competition_match", columnList = "competition_id, match_id"))
public class KillCompetitionMatchResult {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "competition_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE) private KillCompetition competition;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "participant_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE) private KillCompetitionParticipant participant;
    @Column(name = "match_id", nullable = false) private String matchId;
    @Column(name = "match_started_at", nullable = false) private Instant matchStartedAt;
    @Column(nullable = false) private int kills;
    @Column private Integer placement;
    @Column(name = "kill_points", nullable = false, columnDefinition = "integer default 0") private int killPoints;
    @Column(name = "placement_points", nullable = false, columnDefinition = "integer default 0") private int placementPoints;
    @Column(name = "total_points", nullable = false, columnDefinition = "integer default 0") private int totalPoints;
    protected KillCompetitionMatchResult() {}
    public KillCompetitionMatchResult(KillCompetition competition, KillCompetitionParticipant participant,
                                      String matchId, Instant matchStartedAt, int kills, int placement,
                                      int killPoints, int placementPoints, int totalPoints) {
        this.competition = competition; this.participant = participant; this.matchId = matchId;
        refresh(matchStartedAt, kills, placement, killPoints, placementPoints, totalPoints);
    }
    public void refresh(Instant matchStartedAt, int kills, int placement,
                        int killPoints, int placementPoints, int totalPoints) {
        this.matchStartedAt = matchStartedAt;
        this.kills = kills;
        this.placement = placement > 0 ? placement : null;
        this.killPoints = killPoints;
        this.placementPoints = placementPoints;
        this.totalPoints = totalPoints;
    }
    public String getMatchId() { return matchId; }
    public Instant getMatchStartedAt() { return matchStartedAt; }
    public KillCompetitionParticipant getParticipant() { return participant; }
    public int getKills() { return kills; }
    public Integer getPlacement() { return placement; }
    public int getKillPoints() { return legacyScoreRow() ? kills : killPoints; }
    public int getPlacementPoints() { return placementPoints; }
    public int getTotalPoints() { return legacyScoreRow() ? kills : totalPoints; }
    private boolean legacyScoreRow() {
        return placement == null && kills > 0 && killPoints == 0 && placementPoints == 0 && totalPoints == 0
                && competition.getKillPoint() == 1 && !competition.isPlacementPointEnabled();
    }
}
