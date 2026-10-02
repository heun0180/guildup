package com.guildup.pubg.repository;

import com.guildup.pubg.domain.PubgStoredMatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PubgStoredMatchRepository extends JpaRepository<PubgStoredMatch, Long> {
    @Query("select match.matchId from PubgStoredMatch match where match.shard = :shard and match.matchId in :matchIds")
    List<String> findExistingMatchIds(@Param("shard") String shard, @Param("matchIds") Collection<String> matchIds);

    Optional<PubgStoredMatch> findByShardAndMatchId(String shard, String matchId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select match from PubgStoredMatch match where match.shard = :shard and match.matchId = :matchId")
    Optional<PubgStoredMatch> findForUpdateByShardAndMatchId(@Param("shard") String shard, @Param("matchId") String matchId);

    @Query("select distinct match from PubgStoredMatch match left join fetch match.players " +
            "where match.shard = :shard and match.startedAt >= :from and match.startedAt < :to and exists (select p.id from PubgStoredMatchPlayer p where p.match = match and p.accountId in :accountIds) order by match.startedAt, match.matchId")
    List<PubgStoredMatch> findWithPlayersBetween(@Param("shard") String shard, @Param("accountIds") Collection<String> accountIds, @Param("from") Instant from, @Param("to") Instant to);

    @Query("select match from PubgStoredMatch match where match.shard = :shard and match.matchId in :matchIds and match.telemetryLoaded = false")
    List<PubgStoredMatch> findTelemetryMissing(@Param("shard") String shard, @Param("matchIds") Collection<String> matchIds);

    @Query("select distinct match from PubgStoredMatch match join match.players player " +
            "where match.shard = :shard and player.accountId in :accountIds and match.telemetryLoaded = false")
    List<PubgStoredMatch> findTelemetryMissingForAccounts(@Param("shard") String shard, @Param("accountIds") Collection<String> accountIds);

    @Query("select match from PubgStoredMatch match where match.shard = :shard and match.matchId in :matchIds and " +
            "match.telemetryLoaded = true and match.telemetryFactVersion < :factVersion")
    List<PubgStoredMatch> findTelemetryUpgradeCandidates(@Param("shard") String shard, @Param("matchIds") Collection<String> matchIds,
                                                          @Param("factVersion") int factVersion);

    @Query("select distinct match from PubgStoredMatch match join match.players player " +
            "where match.shard = :shard and player.accountId in :accountIds and match.telemetryLoaded = true " +
            "and match.telemetryFactVersion < :factVersion")
    List<PubgStoredMatch> findTelemetryUpgradeCandidatesForAccounts(@Param("shard") String shard, @Param("accountIds") Collection<String> accountIds,
                                                                     @Param("factVersion") int factVersion);
}
