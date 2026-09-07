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

/**
 * GuildUp 커뮤니티와 Discord 서버의 1:1 연결 정보를 보관하는 JPA 엔티티다.
 * 멤버 역할 ID도 이 연결에 함께 설정할 수 있다.
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

    @Column(name = "discord_member_role_id")
    private String discordMemberRoleId;

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

    public String getDiscordMemberRoleId() {
        return discordMemberRoleId;
    }

    /** 같은 연결의 서버 이름 등 최신 Discord 정보를 갱신한다. */
    public void updateGuild(String discordGuildId, String discordGuildName) {
        this.discordGuildId = discordGuildId;
        this.discordGuildName = discordGuildName;
    }

    /** Discord 연결의 멤버 역할 ID를 설정한다. */
    public void configureMemberRole(String discordMemberRoleId) {
        this.discordMemberRoleId = discordMemberRoleId;
    }
}
