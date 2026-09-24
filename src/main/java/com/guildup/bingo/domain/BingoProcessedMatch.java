package com.guildup.bingo.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "bingo_processed_matches", uniqueConstraints = @UniqueConstraint(
        name = "uk_bingo_processed_event_player_match", columnNames = {"bingo_event_id", "participant_id", "match_id"}))
public class BingoProcessedMatch {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "bingo_event_id", nullable = false) private BingoEvent event;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "participant_id", nullable = false) private BingoParticipant participant;
    @Column(name = "match_id", nullable = false) private String matchId;
    @Column(name = "match_started_at", nullable = false) private Instant matchStartedAt;
    @Column(name = "processed_at", nullable = false) private Instant processedAt;
    protected BingoProcessedMatch() {}
    public BingoProcessedMatch(BingoEvent event, BingoParticipant participant, String matchId, Instant matchStartedAt, Instant processedAt) {
        this.event = event; this.participant = participant; this.matchId = matchId;
        this.matchStartedAt = matchStartedAt; this.processedAt = processedAt;
    }
    public BingoParticipant getParticipant() { return participant; }
    public String getMatchId() { return matchId; }
}
