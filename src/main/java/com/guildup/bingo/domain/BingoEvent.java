package com.guildup.bingo.domain;

import com.guildup.community.domain.Community;
import com.guildup.community.domain.CommunityUser;
import com.guildup.community.domain.CommunityGame;
import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "bingo_events", indexes = {
        @Index(name = "idx_bingo_event_community_status", columnList = "community_id,status,starts_at,ends_at")
})
public class BingoEvent {
    public static final Duration SETTLEMENT_GRACE = Duration.ofMinutes(30);
    public static final Duration AGGREGATION_COOLDOWN = Duration.ofMinutes(30);

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "community_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE) private Community community;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "community_game_id", nullable = false)
    private CommunityGame communityGame;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "created_by_community_user_id", nullable = false)
    private CommunityUser createdBy;
    @Column(nullable = false, length = 100) private String title;
    @Column(length = 1000) private String description;
    @Column(name = "board_size", nullable = false) private int boardSize;
    @Column(name = "target_lines", nullable = false) private int targetLines;
    @Column(name = "blackout_enabled", nullable = false) private boolean blackoutEnabled;
    @Column(name = "allow_late_join", nullable = false) private boolean allowLateJoin;
    @Column(name = "starts_at", nullable = false) private Instant startsAt;
    @Column(name = "ends_at", nullable = false) private Instant endsAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private BingoStatus status;
    @Column(name = "last_aggregated_at") private Instant lastAggregatedAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version private long version;
    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position asc") private List<BingoCell> cells = new ArrayList<>();

    protected BingoEvent() {}

    public BingoEvent(Community community, CommunityGame communityGame, CommunityUser createdBy, String title, String description,
                      int boardSize, int targetLines, boolean blackoutEnabled, boolean allowLateJoin,
                      Instant startsAt, Instant endsAt, BingoStatus status, Instant now) {
        this.community = community; this.communityGame = communityGame; this.createdBy = createdBy; this.title = title;
        this.description = description; this.boardSize = boardSize; this.targetLines = targetLines;
        this.blackoutEnabled = blackoutEnabled; this.allowLateJoin = allowLateJoin;
        this.startsAt = startsAt; this.endsAt = endsAt; this.status = status;
        this.createdAt = now; this.updatedAt = now;
    }

    public void addCell(BingoCell cell) { cells.add(cell); }
    public void replaceCells(List<BingoCell> replacements) { cells.clear(); cells.addAll(replacements); }
    public void refreshStatus(Instant now) {
        if (status == BingoStatus.SCHEDULED && !now.isBefore(startsAt)) status = BingoStatus.ACTIVE;
        if (status == BingoStatus.ACTIVE && !now.isBefore(endsAt)) status = BingoStatus.SETTLING;
    }
    public void updateDraft(String title, String description, int boardSize, int targetLines,
                            boolean blackoutEnabled, boolean allowLateJoin, Instant startsAt,
                            Instant endsAt, BingoStatus status, Instant now) {
        this.title = title; this.description = description; this.boardSize = boardSize;
        this.targetLines = targetLines; this.blackoutEnabled = blackoutEnabled;
        this.allowLateJoin = allowLateJoin; this.startsAt = startsAt; this.endsAt = endsAt;
        this.status = status; this.updatedAt = now;
    }
    public void updateActive(String description, Instant endsAt, Instant now) {
        this.description = description; this.endsAt = endsAt; this.updatedAt = now;
    }
    public void aggregated(Instant now) { lastAggregatedAt = now; updatedAt = now; }
    public void complete(Instant now) { status = BingoStatus.COMPLETED; completedAt = now; updatedAt = now; }
    public void cancel(Instant now) { status = BingoStatus.CANCELLED; updatedAt = now; }

    public Long getId() { return id; }
    public Community getCommunity() { return community; }
    public CommunityGame getCommunityGame() { return communityGame; }
    public CommunityUser getCreatedBy() { return createdBy; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public int getBoardSize() { return boardSize; }
    public int getTargetLines() { return targetLines; }
    public boolean isBlackoutEnabled() { return blackoutEnabled; }
    public boolean isAllowLateJoin() { return allowLateJoin; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public BingoStatus getStatus() { return status; }
    public Instant getLastAggregatedAt() { return lastAggregatedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public List<BingoCell> getCells() { return List.copyOf(cells); }
}
