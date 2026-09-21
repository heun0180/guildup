package com.guildup.bingo.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "bingo_processed_sources", uniqueConstraints = @UniqueConstraint(
        name = "uk_bingo_processed_source", columnNames = {
        "bingo_event_id", "participant_id", "source_type", "source_id"
}))
public class BingoProcessedSource {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bingo_event_id", nullable = false) private BingoEvent event;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "participant_id", nullable = false) private BingoParticipant participant;
    @Enumerated(EnumType.STRING) @Column(name = "source_type", nullable = false, length = 30)
    private BingoProgressSourceType sourceType;
    @Column(name = "source_id", nullable = false, length = 100) private String sourceId;
    @Column(name = "occurred_at", nullable = false) private Instant occurredAt;
    @Column(name = "processed_at", nullable = false) private Instant processedAt;

    protected BingoProcessedSource() {}

    public BingoProcessedSource(BingoEvent event, BingoParticipant participant,
                                BingoProgressSourceType sourceType, String sourceId,
                                Instant occurredAt, Instant processedAt) {
        this.event = event;
        this.participant = participant;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.occurredAt = occurredAt;
        this.processedAt = processedAt;
    }
}
