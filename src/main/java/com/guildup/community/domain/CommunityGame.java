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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** 커뮤니티와 활동 게임의 다대일 연결을 나타낸다. */
@Entity
@Table(
        name = "community_games",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_community_game",
                columnNames = {"community_id", "game_type"}
        )
)
public class CommunityGame {

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

    protected CommunityGame() {
    }

    public CommunityGame(Community community, GameType gameType) {
        this.community = community;
        this.gameType = gameType;
    }

    public Long getId() {
        return id;
    }

    public Community getCommunity() {
        return community;
    }

    public GameType getGameType() {
        return gameType;
    }
}
