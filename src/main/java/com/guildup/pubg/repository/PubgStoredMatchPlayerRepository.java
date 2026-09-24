package com.guildup.pubg.repository;

import com.guildup.pubg.domain.PubgStoredMatchPlayer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PubgStoredMatchPlayerRepository extends JpaRepository<PubgStoredMatchPlayer, Long> {
    List<PubgStoredMatchPlayer> findByMatchId(Long matchId);
}
