package com.guildup.pubg.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "pubg_match_kills", indexes = {
        @Index(name = "idx_pubg_match_kills_match_killer", columnList = "pubg_match_id,killer_account_id"),
        @Index(name = "idx_pubg_match_kills_occurred", columnList = "occurred_at")
})
public class PubgStoredMatchKill {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "pubg_match_id", nullable = false)
    private PubgStoredMatch match;
    @Column(name = "killer_account_id", nullable = false, length = 100) private String killerAccountId;
    @Column(name = "victim_account_id", length = 100) private String victimAccountId;
    @Column(length = 150) private String weapon;
    @Column(name = "weapon_category", length = 80) private String weaponCategory;
    @Column(length = 150) private String throwable;
    @Column(name = "distance_meters", nullable = false, precision = 12, scale = 3) private BigDecimal distanceMeters;
    @Column(name = "wall_penetration", nullable = false) private boolean wallPenetration;
    @Column(name = "occurred_at") private Instant occurredAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected PubgStoredMatchKill() {}
    public PubgStoredMatchKill(PubgStoredMatch match, String killerAccountId, String victimAccountId,
                               String weapon, String weaponCategory, String throwable, double distanceMeters,
                               boolean wallPenetration, Instant occurredAt, Instant createdAt) {
        this.match = match; this.killerAccountId = killerAccountId; this.victimAccountId = victimAccountId;
        this.weapon = weapon; this.weaponCategory = weaponCategory; this.throwable = throwable;
        this.distanceMeters = BigDecimal.valueOf(distanceMeters); this.wallPenetration = wallPenetration;
        this.occurredAt = occurredAt; this.createdAt = createdAt;
    }
    public String getKillerAccountId() { return killerAccountId; }
    public PubgStoredMatch getMatch() { return match; }
    public String getVictimAccountId() { return victimAccountId; }
    public String getWeapon() { return weapon; }
    public String getWeaponCategory() { return weaponCategory; }
    public String getThrowable() { return throwable; }
    public BigDecimal getDistanceMeters() { return distanceMeters; }
    public boolean isWallPenetration() { return wallPenetration; }
    public Instant getOccurredAt() { return occurredAt; }
}
