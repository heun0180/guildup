package com.guildup.community.repository;

import com.guildup.community.domain.DiscordCommunityConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 커뮤니티와 Discord 서버의 연결 엔티티를 조회하고 저장한다. */
public interface DiscordCommunityConnectionRepository
        extends JpaRepository<DiscordCommunityConnection, Long> {

    /** 특정 커뮤니티의 Discord 연결을 조회한다. */
    Optional<DiscordCommunityConnection> findByCommunityId(Long communityId);

    /** Discord 서버가 이미 다른 커뮤니티에 연결됐는지 확인할 때 사용한다. */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "community")
    Optional<DiscordCommunityConnection> findByDiscordGuildId(String discordGuildId);

    /** 봇 시작 복구에서 트랜잭션 밖에서도 Community ID를 안전하게 읽는다. */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "community")
    @org.springframework.data.jpa.repository.Query("select connection from DiscordCommunityConnection connection")
    List<DiscordCommunityConnection> findAllWithCommunity();
}
