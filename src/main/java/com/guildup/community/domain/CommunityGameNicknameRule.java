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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

/** 커뮤니티와 게임별로 활성화된 Discord 닉네임 추출 규칙이다. */
@Entity
@Table(
        name = "community_game_nickname_rules",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_community_game_nickname_rule",
                columnNames = {"community_id", "game_type"}
        )
)
public class CommunityGameNicknameRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Community community;

    @Enumerated(EnumType.STRING)
    @Column(name = "game_type", nullable = false)
    private GameType gameType;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy_type", nullable = false)
    private GameNicknameRuleStrategyType strategyType;

    @Enumerated(EnumType.STRING)
    @Column(name = "delimiter_type")
    private GameNicknameDelimiterType delimiterType;

    @Column(name = "segment_index")
    private Integer segmentIndex;

    @Column(name = "from_end", nullable = false)
    private boolean fromEnd;

    @Column(name = "expected_segment_count")
    private Integer expectedSegmentCount;

    @Column(name = "sample_discord_nickname", nullable = false)
    private String sampleDiscordNickname;

    @Column(name = "sample_game_nickname", nullable = false)
    private String sampleGameNickname;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CommunityGameNicknameRule() {
    }

    public CommunityGameNicknameRule(
            Community community,
            GameType gameType,
            GameNicknameRuleStrategyType strategyType,
            GameNicknameDelimiterType delimiterType,
            Integer segmentIndex,
            boolean fromEnd,
            Integer expectedSegmentCount,
            String sampleDiscordNickname,
            String sampleGameNickname
    ) {
        this.community = community;
        this.gameType = gameType;
        configure(strategyType, delimiterType, segmentIndex, fromEnd, expectedSegmentCount,
                sampleDiscordNickname, sampleGameNickname);
    }

    public void configure(
            GameNicknameRuleStrategyType strategyType,
            GameNicknameDelimiterType delimiterType,
            Integer segmentIndex,
            boolean fromEnd,
            Integer expectedSegmentCount,
            String sampleDiscordNickname,
            String sampleGameNickname
    ) {
        this.strategyType = strategyType;
        this.delimiterType = delimiterType;
        this.segmentIndex = segmentIndex;
        this.fromEnd = fromEnd;
        this.expectedSegmentCount = expectedSegmentCount;
        this.sampleDiscordNickname = sampleDiscordNickname;
        this.sampleGameNickname = sampleGameNickname;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Community getCommunity() { return community; }
    public GameType getGameType() { return gameType; }
    public GameNicknameRuleStrategyType getStrategyType() { return strategyType; }
    public GameNicknameDelimiterType getDelimiterType() { return delimiterType; }
    public Integer getSegmentIndex() { return segmentIndex; }
    public boolean isFromEnd() { return fromEnd; }
    public Integer getExpectedSegmentCount() { return expectedSegmentCount; }
    public String getSampleDiscordNickname() { return sampleDiscordNickname; }
    public String getSampleGameNickname() { return sampleGameNickname; }
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
