package com.guildup.bingo.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "bingo_progress", uniqueConstraints = @UniqueConstraint(
        name = "uk_bingo_progress_participant_cell", columnNames = {"participant_id", "bingo_cell_id"}))
public class BingoProgress {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "participant_id", nullable = false) private BingoParticipant participant;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "bingo_cell_id", nullable = false) private BingoCell cell;
    @Column(name = "current_value", nullable = false, precision = 18, scale = 3) private BigDecimal currentValue = BigDecimal.ZERO;
    @Column(name = "occurrence_count", nullable = false) private int occurrenceCount;
    @Column(nullable = false) private boolean completed;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "evidence_match_id") private String evidenceMatchId;
    @Column(name = "evidence_event_at") private Instant evidenceEventAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version private long version;

    protected BingoProgress() {}
    public BingoProgress(BingoParticipant participant, BingoCell cell, Instant now) {
        this.participant = participant; this.cell = cell; this.updatedAt = now;
    }
    public void apply(BigDecimal value, int occurrences, boolean complete, String matchId, Instant evidenceAt, Instant now) {
        currentValue = value; occurrenceCount = occurrences; updatedAt = now;
        if (complete && !completed) {
            completed = true; completedAt = evidenceAt == null ? now : evidenceAt;
            evidenceMatchId = matchId; evidenceEventAt = evidenceAt;
        }
    }
    /** TEMPORARY rebuild hook: 계산이 모두 끝난 snapshot으로 누적 상태를 원자적으로 교체한다. */
    public void replaceSnapshot(BigDecimal value, int occurrences, boolean complete,
                                Instant completedAt, String matchId, Instant evidenceAt, Instant now) {
        currentValue = value; occurrenceCount = occurrences; completed = complete;
        this.completedAt = complete ? completedAt : null;
        evidenceMatchId = complete ? matchId : null;
        evidenceEventAt = complete ? evidenceAt : null;
        updatedAt = now;
    }
    public Long getId() { return id; }
    public BingoParticipant getParticipant() { return participant; }
    public BingoCell getCell() { return cell; }
    public BigDecimal getCurrentValue() { return currentValue; }
    public int getOccurrenceCount() { return occurrenceCount; }
    public boolean isCompleted() { return completed; }
    public Instant getCompletedAt() { return completedAt; }
    public String getEvidenceMatchId() { return evidenceMatchId; }
    public Instant getEvidenceEventAt() { return evidenceEventAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
