package com.guildup.bingo.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "bingo_line_completions", uniqueConstraints = @UniqueConstraint(
        name = "uk_bingo_line_participant_key", columnNames = {"participant_id", "line_key"}))
public class BingoLineCompletion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "participant_id", nullable = false) private BingoParticipant participant;
    @Column(name = "line_key", nullable = false, length = 20) private String lineKey;
    @Column(name = "completed_at", nullable = false) private Instant completedAt;
    protected BingoLineCompletion() {}
    public BingoLineCompletion(BingoParticipant participant, String lineKey, Instant completedAt) {
        this.participant = participant; this.lineKey = lineKey; this.completedAt = completedAt;
    }
    public String getLineKey() { return lineKey; }
    public Instant getCompletedAt() { return completedAt; }
}
