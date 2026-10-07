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
    List<DiscordVoiceSession> findByCommunityIdAndDiscordUserId(Long communityId, String discordUserId);
    List<DiscordVoiceSession> findByDiscordUserId(String discordUserId);

    Optional<DiscordVoiceSession> findByCommunityIdAndDiscordUserIdAndLeftAtIsNull(
            Long communityId,
            String discordUserId
    );

    @EntityGraph(attributePaths = "community")
    List<DiscordVoiceSession> findByCommunityIdAndLeftAtIsNull(Long communityId);

    /** 시작 제한 없이 현재까지의 유효한 세션을 읽는다. */
    @Query("""
            select s from DiscordVoiceSession s
            where s.community.id = :communityId
              and s.discordUserId in :discordUserIds
              and s.joinedAt < :periodEnd
              and (s.leftAt is null or s.leftAt > s.joinedAt)
            order by s.joinedAt desc
            """)
    List<DiscordVoiceSession> findAllSessionsBefore(
            @Param("communityId") Long communityId,
            @Param("discordUserIds") Collection<String> discordUserIds,
            @Param("periodEnd") Instant periodEnd
    );

    /** [periodStart, periodEnd)와 양의 시간만큼 겹치는 세션만 읽는다. */
    @Query("""
            select s from DiscordVoiceSession s
            where s.community.id = :communityId
              and s.discordUserId in :discordUserIds
              and s.joinedAt < :periodEnd
              and (s.leftAt is null or s.leftAt > :periodStart)
              and (s.leftAt is null or s.leftAt > s.joinedAt)
            order by s.joinedAt desc
            """)
    List<DiscordVoiceSession> findOverlappingSessions(
            @Param("communityId") Long communityId,
            @Param("discordUserIds") Collection<String> discordUserIds,
            @Param("periodStart") Instant periodStart,
            @Param("periodEnd") Instant periodEnd
    );
}
