package com.guildup.killcompetition.service;

import com.guildup.killcompetition.dto.KillCompetitionDetailResponse;
import com.guildup.pubg.model.PubgPlayer;
import com.guildup.pubg.service.PubgPlayerService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** 계정 행이 없는 클랜원도 닉네임 규칙과 PUBG 확인을 거쳐 즉시 참가할 수 있게 한다. */
@Service
public class KillCompetitionParticipationService {
    private final KillCompetitionParticipationStore store;
    private final PubgPlayerService players;
    private final KillCompetitionService competitions;

    public KillCompetitionParticipationService(KillCompetitionParticipationStore store,
                                               PubgPlayerService players,
                                               KillCompetitionService competitions) {
        this.store = store;
        this.players = players;
        this.competitions = competitions;
    }

    public KillCompetitionDetailResponse join(Long userId, Long communityId, Long competitionId) {
        var preparation = store.prepare(userId, communityId, competitionId);
        PubgPlayer resolved = null;
        if (preparation.requiresLookup()) {
            resolved = players.findByNamesFresh(preparation.shard(), List.of(preparation.nickname())).stream()
                    .filter(player -> player.name() != null
                            && player.name().equalsIgnoreCase(preparation.nickname()))
                    .filter(player -> player.accountId() != null && !player.accountId().isBlank())
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                            "PUBG에서 '" + preparation.nickname()
                                    + "' 계정을 찾지 못했습니다. 플랫폼과 닉네임을 확인해 주세요."));
        }
        store.commit(userId, communityId, competitionId, preparation, resolved);
        return competitions.get(userId, communityId, competitionId);
    }
}
