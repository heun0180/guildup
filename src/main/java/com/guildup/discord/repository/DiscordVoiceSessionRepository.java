package com.guildup.discord.repository;

import com.guildup.discord.domain.DiscordVoiceSession;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DiscordVoiceSessionRepository extends JpaRepository<DiscordVoiceSession, Long> {

    Optional<DiscordVoiceSession> findByCommunityIdAndDiscordUserIdAndLeftAtIsNull(
            Long communityId,
            String discordUserId
    );

    @EntityGraph(attributePaths = "community")
    List<DiscordVoiceSession> findByCommunityIdAndLeftAtIsNull(Long communityId);

    /** 기간 안에 시작했거나 기간 시작 전에 열려 있어 조회 구간과 겹치는 세션만 읽는다. */
    @Query("""
            select s from DiscordVoiceSession s
            where s.community.id = :communityId
              and s.discordUserId in :discordUserIds
              and s.joinedAt <= :periodEnd
              and (s.leftAt is null or s.leftAt > :periodStart)
            order by s.joinedAt desc
            """)
    List<DiscordVoiceSession> findOverlappingSessions(
            @Param("communityId") Long communityId,
            @Param("discordUserIds") Collection<String> discordUserIds,
            @Param("periodStart") Instant periodStart,
            @Param("periodEnd") Instant periodEnd
    );
}
