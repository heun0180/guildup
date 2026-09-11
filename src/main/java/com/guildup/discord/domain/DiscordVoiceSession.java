package com.guildup.discord.domain;

import com.guildup.community.domain.Community;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

/** 연결된 Discord 서버에서 한 사용자가 한 음성 채널에 머문 구간이다. */
@Entity
@Table(
        name = "discord_voice_sessions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_discord_voice_session_open",
                columnNames = {"community_id", "discord_user_id", "open_session_marker"}
        ),
        indexes = {
                @Index(
                        name = "idx_voice_session_community_user_joined",
                        columnList = "community_id, discord_user_id, joined_at"
                ),
                @Index(
                        name = "idx_voice_session_community_joined",
                        columnList = "community_id, joined_at"
                )
        }
)
public class DiscordVoiceSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Community community;

    @Column(name = "discord_guild_id", nullable = false)
    private String discordGuildId;

    @Column(name = "discord_user_id", nullable = false)
    private String discordUserId;

    @Column(name = "discord_channel_id", nullable = false)
    private String discordChannelId;

    @Column(name = "channel_name_snapshot")
    private String channelNameSnapshot;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "left_at")
    private Instant leftAt;

    /** 열린 행만 TRUE이고 종료된 행은 NULL이라 여러 과거 행과 하나의 열린 행을 함께 허용한다. */
    @Column(name = "open_session_marker")
    private Boolean openSessionMarker;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DiscordVoiceSession() {
    }

    public DiscordVoiceSession(
            Community community,
            String discordGuildId,
            String discordUserId,
            String discordChannelId,
            String channelNameSnapshot,
            Instant joinedAt
    ) {
        this.community = community;
        this.discordGuildId = discordGuildId;
        this.discordUserId = discordUserId;
        this.discordChannelId = discordChannelId;
        this.channelNameSnapshot = channelNameSnapshot;
        this.joinedAt = joinedAt;
        this.openSessionMarker = Boolean.TRUE;
    }

    public Long getId() { return id; }
    public Community getCommunity() { return community; }
    public String getDiscordGuildId() { return discordGuildId; }
    public String getDiscordUserId() { return discordUserId; }
    public String getDiscordChannelId() { return discordChannelId; }
    public String getChannelNameSnapshot() { return channelNameSnapshot; }
    public Instant getJoinedAt() { return joinedAt; }
    public Instant getLeftAt() { return leftAt; }
    public boolean isOpen() { return leftAt == null; }

    /** 이벤트 시각이 입장 시각보다 빠를 수 없도록 보정해 세션을 종료한다. */
    public void close(Instant eventTime) {
        if (!isOpen()) return;
        leftAt = eventTime.isBefore(joinedAt) ? joinedAt : eventTime;
        openSessionMarker = null;
        updatedAt = eventTime;
    }

    @PrePersist
    void initializeTimestamps() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void updateTimestamp() {
        if (updatedAt == null) updatedAt = Instant.now();
    }
}
