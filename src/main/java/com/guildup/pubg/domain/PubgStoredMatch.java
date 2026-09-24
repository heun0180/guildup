package com.guildup.pubg.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** GuildUp 콘텐츠가 함께 재사용하는 PUBG 경기 원본 요약이다. */
@Entity
@Table(name = "pubg_matches", uniqueConstraints = @UniqueConstraint(
        name = "uk_pubg_matches_match_id", columnNames = "match_id"), indexes = {
        @Index(name = "idx_pubg_matches_started_at", columnList = "started_at"),
        @Index(name = "idx_pubg_matches_shard_started", columnList = "shard,started_at")
})
public class PubgStoredMatch {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "match_id", nullable = false, length = 100) private String matchId;
    @Column(nullable = false, length = 30) private String shard;
    @Column(name = "started_at", nullable = false) private Instant startedAt;
    @Column private Integer duration;
    @Column(name = "game_mode", length = 50) private String gameMode;
    @Column(name = "match_type", length = 50) private String matchType;
    @Column(name = "map_name", length = 100) private String mapName;
    @Column(name = "custom_match") private Boolean customMatch;
    @Column(name = "telemetry_url", length = 1000) private String telemetryUrl;
    @Column(name = "telemetry_loaded", nullable = false) private boolean telemetryLoaded;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @OneToMany(mappedBy = "match", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PubgStoredMatchPlayer> players = new ArrayList<>();
    @OneToMany(mappedBy = "match", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PubgStoredMatchKill> kills = new ArrayList<>();

    protected PubgStoredMatch() {}

    public PubgStoredMatch(String matchId, String shard, Instant startedAt, String gameMode,
                           String matchType, String mapName, Boolean customMatch,
                           String telemetryUrl, Integer duration, Instant now) {
        this.matchId = matchId; this.shard = shard; this.startedAt = startedAt;
        this.gameMode = gameMode; this.matchType = matchType; this.mapName = mapName;
        this.customMatch = customMatch; this.telemetryUrl = telemetryUrl;
        this.duration = duration;
        this.createdAt = now; this.updatedAt = now;
    }

    public void addPlayer(PubgStoredMatchPlayer player) { players.add(player); }
    public void replaceTelemetry(List<PubgStoredMatchKill> values, Instant now) {
        kills.clear(); kills.addAll(values); telemetryLoaded = true; updatedAt = now;
    }
    public Long getId() { return id; }
    public String getMatchId() { return matchId; }
    public String getShard() { return shard; }
    public Instant getStartedAt() { return startedAt; }
    public String getGameMode() { return gameMode; }
    public String getMatchType() { return matchType; }
    public String getMapName() { return mapName; }
    public Boolean getCustomMatch() { return customMatch; }
    public String getTelemetryUrl() { return telemetryUrl; }
    public Integer getDuration() { return duration; }
    public boolean isTelemetryLoaded() { return telemetryLoaded; }
    public List<PubgStoredMatchPlayer> getPlayers() { return players; }
    public List<PubgStoredMatchKill> getKills() { return kills; }
}
