package com.guildup.community.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Duration;
import java.time.Instant;

@Entity
@Table(
        name = "community_game_activity_syncs",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_community_game_activity_sync",
                columnNames = "community_game_id"
        )
)
public class CommunityGameActivitySync {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_game_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private CommunityGame communityGame;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", nullable = false)
    private CommunityGameActivitySyncStatus syncStatus;

    @Column(name = "last_successful_sync_at")
    private Instant lastSuccessfulSyncAt;

    @Column(name = "last_sync_attempt_at")
    private Instant lastSyncAttemptAt;

    protected CommunityGameActivitySync() {
    }

    public CommunityGameActivitySync(CommunityGame communityGame) {
        this.communityGame = communityGame;
        this.syncStatus = CommunityGameActivitySyncStatus.NEVER_SYNCED;
    }

    public void start(Instant now) {
        syncStatus = CommunityGameActivitySyncStatus.SYNCING;
        lastSyncAttemptAt = now;
    }

    public void succeed(Instant now) {
        syncStatus = CommunityGameActivitySyncStatus.SUCCESS;
        lastSuccessfulSyncAt = now;
    }

    public void fail() {
        syncStatus = CommunityGameActivitySyncStatus.FAILED;
    }

    public boolean isSyncingAt(Instant now, Duration staleAfter) {
        return syncStatus == CommunityGameActivitySyncStatus.SYNCING
                && lastSyncAttemptAt != null
                && lastSyncAttemptAt.plus(staleAfter).isAfter(now);
    }

    public Instant successfulLimitUntil(Duration successInterval) {
        return lastSuccessfulSyncAt == null ? null : lastSuccessfulSyncAt.plus(successInterval);
    }

    public Instant failedCooldownUntil(Duration failedCooldown) {
        return syncStatus != CommunityGameActivitySyncStatus.FAILED || lastSyncAttemptAt == null
                ? null : lastSyncAttemptAt.plus(failedCooldown);
    }

    public Long getId() { return id; }
    public CommunityGame getCommunityGame() { return communityGame; }
    public CommunityGameActivitySyncStatus getSyncStatus() { return syncStatus; }
    public Instant getLastSuccessfulSyncAt() { return lastSuccessfulSyncAt; }
    public Instant getLastSyncAttemptAt() { return lastSyncAttemptAt; }
}
