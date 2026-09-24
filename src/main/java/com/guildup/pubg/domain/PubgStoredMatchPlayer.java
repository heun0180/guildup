package com.guildup.pubg.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/** Match API 통계와 Telemetry에서 계산한 플레이어 fact를 함께 보존한다. */
@Entity
@Table(name = "pubg_match_players", uniqueConstraints = @UniqueConstraint(
        name = "uk_pubg_match_players_match_account", columnNames = {"pubg_match_id", "pubg_account_id"}),
        indexes = @Index(name = "idx_pubg_match_players_account", columnList = "pubg_account_id"))
public class PubgStoredMatchPlayer {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "pubg_match_id", nullable = false)
    private PubgStoredMatch match;
    @Column(name = "pubg_account_id", nullable = false, length = 100) private String accountId;
    @Column(name = "player_name", length = 100) private String playerName;
    @Column(name = "team_number", nullable = false) private int teamNumber;
    private int kills;
    @Column(precision = 18, scale = 3) private BigDecimal damage;
    private int assists;
    private int dbnos;
    @Column(name = "headshot_kills") private int headshotKills;
    private int revives;
    private int heals;
    private int boosts;
    @Column(name = "walk_distance", precision = 18, scale = 3) private BigDecimal walkDistance;
    @Column(name = "ride_distance", precision = 18, scale = 3) private BigDecimal rideDistance;
    @Column(name = "swim_distance", precision = 18, scale = 3) private BigDecimal swimDistance;
    private int placement;
    @Column(nullable = false) private boolean win;
    @Lob @Column(name = "metrics_json") private String metricsJson;
    @Lob @Column(name = "throwable_uses_json") private String throwableUsesJson;
    @Lob @Column(name = "picked_items_json") private String pickedItemsJson;
    @Lob @Column(name = "used_items_json") private String usedItemsJson;
    @Lob @Column(name = "care_package_items_json") private String carePackageItemsJson;
    @Lob @Column(name = "destroyed_armor_json") private String destroyedArmorJson;
    @Column(name = "latest_evidence_at") private Instant latestEvidenceAt;
    @Column(name = "telemetry_clan_members") private Integer telemetryClanMembers;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected PubgStoredMatchPlayer() {}

    public PubgStoredMatchPlayer(PubgStoredMatch match, String accountId, String playerName, int teamNumber,
                                 int kills, BigDecimal damage, int assists, int dbnos, int headshotKills,
                                 int revives, int heals, int boosts, BigDecimal walkDistance,
                                 BigDecimal rideDistance, BigDecimal swimDistance, int placement, Instant now) {
        this.match = match; this.accountId = accountId; this.playerName = playerName; this.teamNumber = teamNumber;
        this.kills = kills; this.damage = damage; this.assists = assists; this.dbnos = dbnos;
        this.headshotKills = headshotKills; this.revives = revives; this.heals = heals; this.boosts = boosts;
        this.walkDistance = walkDistance; this.rideDistance = rideDistance; this.swimDistance = swimDistance;
        this.placement = placement; this.win = placement == 1; this.createdAt = now; this.updatedAt = now;
    }

    public void telemetryFacts(String metricsJson, String throwableUsesJson, String pickedItemsJson,
                               String usedItemsJson, String carePackageItemsJson, String destroyedArmorJson,
                               int telemetryClanMembers, Instant latestEvidenceAt, Instant now) {
        this.metricsJson = metricsJson; this.throwableUsesJson = throwableUsesJson;
        this.pickedItemsJson = pickedItemsJson; this.usedItemsJson = usedItemsJson;
        this.carePackageItemsJson = carePackageItemsJson; this.destroyedArmorJson = destroyedArmorJson;
        this.latestEvidenceAt = latestEvidenceAt; this.updatedAt = now;
        this.telemetryClanMembers = telemetryClanMembers;
    }
    public PubgStoredMatch getMatch() { return match; }
    public String getAccountId() { return accountId; }
    public String getPlayerName() { return playerName; }
    public int getTeamNumber() { return teamNumber; }
    public int getKills() { return kills; }
    public BigDecimal getDamage() { return damage; }
    public int getAssists() { return assists; }
    public int getDbnos() { return dbnos; }
    public int getHeadshotKills() { return headshotKills; }
    public int getRevives() { return revives; }
    public int getHeals() { return heals; }
    public int getBoosts() { return boosts; }
    public BigDecimal getWalkDistance() { return walkDistance; }
    public BigDecimal getRideDistance() { return rideDistance; }
    public BigDecimal getSwimDistance() { return swimDistance; }
    public int getPlacement() { return placement; }
    public String getMetricsJson() { return metricsJson; }
    public String getThrowableUsesJson() { return throwableUsesJson; }
    public String getPickedItemsJson() { return pickedItemsJson; }
    public String getUsedItemsJson() { return usedItemsJson; }
    public String getCarePackageItemsJson() { return carePackageItemsJson; }
    public String getDestroyedArmorJson() { return destroyedArmorJson; }
    public Instant getLatestEvidenceAt() { return latestEvidenceAt; }
    public Integer getTelemetryClanMembers() { return telemetryClanMembers; }
}
