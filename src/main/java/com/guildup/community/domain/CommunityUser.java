package com.guildup.community.domain;

import com.guildup.user.domain.User;
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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** GuildUp 사용자와 커뮤니티의 사용·관리 관계를 나타낸다. */
@Entity
@Table(
        name = "community_users",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_community_user",
                columnNames = {"community_id", "user_id"}
        )
)
public class CommunityUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Community community;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CommunityUserRole role;

    protected CommunityUser() {
    }

    public CommunityUser(Community community, User user, CommunityUserRole role) {
        this.community = community;
        this.user = user;
        this.role = role;
    }

    public Long getId() {
        return id;
    }

    public Community getCommunity() {
        return community;
    }

    public User getUser() {
        return user;
    }

    public CommunityUserRole getRole() {
        return role;
    }
}
