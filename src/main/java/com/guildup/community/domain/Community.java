package com.guildup.community.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "communities")
public class Community {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "discord_guild_id", unique = true)
    private String discordGuildId;

    @Column(name = "discord_member_role_id")
    private String discordMemberRoleId;

    protected Community() {
    }

    public Community(String name, String discordGuildId) {
        this.name = name;
        this.discordGuildId = discordGuildId;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDiscordGuildId() {
        return discordGuildId;
    }

    public String getDiscordMemberRoleId() {
        return discordMemberRoleId;
    }

    public void configureDiscordMemberRole(String discordMemberRoleId) {
        this.discordMemberRoleId = discordMemberRoleId;
    }
}
