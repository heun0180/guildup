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
    @Query("select match.matchId from PubgStoredMatch match where match.matchId in :matchIds")
    List<String> findExistingMatchIds(@Param("matchIds") Collection<String> matchIds);

    Optional<PubgStoredMatch> findByMatchId(String matchId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select match from PubgStoredMatch match where match.matchId = :matchId")
    Optional<PubgStoredMatch> findForUpdateByMatchId(@Param("matchId") String matchId);

    @Query("select distinct match from PubgStoredMatch match left join fetch match.players " +
            "where match.startedAt >= :from and match.startedAt < :to order by match.startedAt, match.matchId")
    List<PubgStoredMatch> findWithPlayersBetween(@Param("from") Instant from, @Param("to") Instant to);

    @Query("select match from PubgStoredMatch match where match.matchId in :matchIds and match.telemetryLoaded = false")
    List<PubgStoredMatch> findTelemetryMissing(@Param("matchIds") Collection<String> matchIds);
}
