package com.guildup.killcompetition.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import java.time.Instant;

@Entity
@Table(name = "kill_competition_match_results", uniqueConstraints = @UniqueConstraint(
        name = "uk_kill_competition_match_participant", columnNames = {"competition_id", "match_id", "participant_id"}
), indexes = @Index(name = "idx_kill_competition_match", columnList = "competition_id, match_id"))
public class KillCompetitionMatchResult {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "competition_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE) private KillCompetition competition;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "participant_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE) private KillCompetitionParticipant participant;
    @Column(name = "match_id", nullable = false) private String matchId;
    @Column(name = "match_started_at", nullable = false) private Instant matchStartedAt;
    @Column(nullable = false) private int kills;
    protected KillCompetitionMatchResult() {}
    public KillCompetitionMatchResult(KillCompetition competition, KillCompetitionParticipant participant,
                                      String matchId, Instant matchStartedAt, int kills) {
        this.competition = competition; this.participant = participant; this.matchId = matchId;
        this.matchStartedAt = matchStartedAt; this.kills = kills;
    }
    public void refresh(Instant matchStartedAt, int kills) {
        this.matchStartedAt = matchStartedAt;
        this.kills = kills;
    }
    public String getMatchId() { return matchId; }
    public Instant getMatchStartedAt() { return matchStartedAt; }
    public KillCompetitionParticipant getParticipant() { return participant; }
    public int getKills() { return kills; }
}
