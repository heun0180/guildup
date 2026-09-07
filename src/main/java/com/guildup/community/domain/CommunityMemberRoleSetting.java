package com.guildup.community.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

/** Discord 멤버를 클랜원으로 판단할 커뮤니티별 역할 설정이다. */
@Entity
@Table(
        name = "community_member_role_settings",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_community_member_role_setting",
                columnNames = {"community_id", "discord_role_id"}
        )
)
public class CommunityMemberRoleSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Community community;

    @Column(name = "discord_role_id", nullable = false)
    private String discordRoleId;

    @Column(name = "discord_role_name", nullable = false)
    private String discordRoleName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CommunityMemberRoleSetting() {
    }

    public CommunityMemberRoleSetting(Community community, String discordRoleId, String discordRoleName) {
        this.community = community;
        this.discordRoleId = discordRoleId;
        this.discordRoleName = discordRoleName;
    }

    public Long getId() {
        return id;
    }

    public Community getCommunity() {
        return community;
    }

    public String getDiscordRoleId() {
        return discordRoleId;
    }

    public String getDiscordRoleName() {
        return discordRoleName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @PrePersist
    void initializeCreatedAt() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
