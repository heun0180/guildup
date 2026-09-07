package com.guildup.community.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

/**
 * GuildUp 커뮤니티와 Discord 서버의 1:1 연결 정보를 보관하는 JPA 엔티티다.
 * 마지막 Discord 멤버 동기화 시간도 함께 관리한다.
 */
@Entity
@Table(name = "discord_community_connections")
public class DiscordCommunityConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 하나의 커뮤니티에는 Discord 연결을 하나만 둘 수 있다.
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_id", nullable = false, unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Community community;

    // 하나의 Discord 서버가 여러 GuildUp 커뮤니티에 중복 연결되는 것을 막는다.
    @Column(name = "discord_guild_id", nullable = false, unique = true)
    private String discordGuildId;

    @Column(name = "discord_guild_name")
    private String discordGuildName;

    @Column(name = "last_member_synced_at")
    private Instant lastMemberSyncedAt;

    protected DiscordCommunityConnection() {
    }

    public DiscordCommunityConnection(Community community, String discordGuildId, String discordGuildName) {
        this.community = community;
        this.discordGuildId = discordGuildId;
        this.discordGuildName = discordGuildName;
    }

    public Long getId() {
        return id;
    }

    public Community getCommunity() {
        return community;
    }

    public String getDiscordGuildId() {
        return discordGuildId;
    }

    public String getDiscordGuildName() {
        return discordGuildName;
    }

    public Instant getLastMemberSyncedAt() {
        return lastMemberSyncedAt;
    }

    /** 같은 연결의 서버 이름 등 최신 Discord 정보를 갱신한다. */
    public void updateGuild(String discordGuildId, String discordGuildName) {
        this.discordGuildId = discordGuildId;
        this.discordGuildName = discordGuildName;
    }

    /** 성공한 Discord 클랜원 동기화 시간을 기록한다. */
    public void markMembersSynced(Instant synchronizedAt) {
        this.lastMemberSyncedAt = synchronizedAt;
    }
}
