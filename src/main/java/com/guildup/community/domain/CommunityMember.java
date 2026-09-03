package com.guildup.community.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.Objects;

@Entity
@Table(
        name = "community_members",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_community_member_discord_user",
                columnNames = {"community_id", "discord_user_id"}
        )
)
public class CommunityMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_id", nullable = false)
    private Community community;

    @Column(nullable = false)
    private String nickname;

    @Column(name = "discord_user_id")
    private String discordUserId;

    protected CommunityMember() {
    }

    public CommunityMember(Community community, String nickname) {
        this(community, nickname, null);
    }

    public CommunityMember(Community community, String nickname, String discordUserId) {
        this.community = community;
        this.nickname = nickname;
        this.discordUserId = discordUserId;
    }

    public Long getId() {
        return id;
    }

    public String getNickname() {
        return nickname;
    }

    public String getDiscordUserId() {
        return discordUserId;
    }

    public boolean updateNickname(String nickname) {
        if (Objects.equals(this.nickname, nickname)) {
            return false;
        }

        this.nickname = nickname;
        return true;
    }
}
