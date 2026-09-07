package com.guildup.community.domain;

import jakarta.persistence.Column;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

/** 커뮤니티가 선택한 게임별 클랜 활동 인정 기준이다. */
@Entity
@Table(
        name = "community_game_activity_rules",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_community_game_activity_rule",
                columnNames = "community_game_id"
        ),
        check = @CheckConstraint(
                name = "ck_community_game_activity_rule_values",
                constraint = "activity_period_days between 1 and 365 "
                        + "and minimum_clan_members_in_roster between 2 and 4"
        )
)
public class CommunityGameActivityRule {

    public static final int DEFAULT_ACTIVITY_PERIOD_DAYS = 14;
    public static final int DEFAULT_MINIMUM_CLAN_MEMBERS_IN_ROSTER = 2;
    public static final int MAX_ACTIVITY_PERIOD_DAYS = 365;
    public static final int MINIMUM_SUPPORTED_CLAN_MEMBERS_IN_ROSTER = 2;
    public static final int MAXIMUM_SUPPORTED_CLAN_MEMBERS_IN_ROSTER = 4;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_game_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private CommunityGame communityGame;

    @Column(name = "activity_period_days", nullable = false)
    private int activityPeriodDays;

    @Column(name = "minimum_clan_members_in_roster", nullable = false)
    private int minimumClanMembersInRoster;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CommunityGameActivityRule() {
    }

    public CommunityGameActivityRule(
            CommunityGame communityGame,
            int activityPeriodDays,
            int minimumClanMembersInRoster
    ) {
        if (communityGame == null) {
            throw new IllegalArgumentException("communityGame is required");
        }
        this.communityGame = communityGame;
        configure(activityPeriodDays, minimumClanMembersInRoster);
    }

    public static CommunityGameActivityRule defaultRule(CommunityGame communityGame) {
        return new CommunityGameActivityRule(
                communityGame,
                DEFAULT_ACTIVITY_PERIOD_DAYS,
                DEFAULT_MINIMUM_CLAN_MEMBERS_IN_ROSTER
        );
    }

    public void configure(int activityPeriodDays, int minimumClanMembersInRoster) {
        validate(activityPeriodDays, minimumClanMembersInRoster);
        this.activityPeriodDays = activityPeriodDays;
        this.minimumClanMembersInRoster = minimumClanMembersInRoster;
        this.updatedAt = Instant.now();
    }

    public boolean isSatisfiedByClanMemberCountInRoster(int clanMemberCountInRoster) {
        return clanMemberCountInRoster >= minimumClanMembersInRoster;
    }

    public static void validate(int activityPeriodDays, int minimumClanMembersInRoster) {
        if (activityPeriodDays < 1 || activityPeriodDays > MAX_ACTIVITY_PERIOD_DAYS) {
            throw new IllegalArgumentException(
                    "활동 확인 기간은 1일 이상 " + MAX_ACTIVITY_PERIOD_DAYS + "일 이하여야 합니다."
            );
        }
        if (minimumClanMembersInRoster < MINIMUM_SUPPORTED_CLAN_MEMBERS_IN_ROSTER
                || minimumClanMembersInRoster > MAXIMUM_SUPPORTED_CLAN_MEMBERS_IN_ROSTER) {
            throw new IllegalArgumentException("활동 인정 인원은 2명, 3명, 4명 중 하나여야 합니다.");
        }
    }

    public Long getId() { return id; }
    public CommunityGame getCommunityGame() { return communityGame; }
    public int getActivityPeriodDays() { return activityPeriodDays; }
    public int getMinimumClanMembersInRoster() { return minimumClanMembersInRoster; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    @PrePersist
    void initializeTimestamps() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void updateTimestamp() {
        updatedAt = Instant.now();
    }
}
