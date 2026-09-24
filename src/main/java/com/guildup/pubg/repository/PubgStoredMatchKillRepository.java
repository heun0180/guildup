package com.guildup.pubg.repository;

import com.guildup.pubg.domain.PubgStoredMatchKill;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Collection;

public interface PubgStoredMatchKillRepository extends JpaRepository<PubgStoredMatchKill, Long> {
    List<PubgStoredMatchKill> findByMatchId(Long matchId);
    List<PubgStoredMatchKill> findByMatchIdIn(Collection<Long> matchIds);
}
