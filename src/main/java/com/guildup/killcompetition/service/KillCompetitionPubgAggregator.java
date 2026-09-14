package com.guildup.killcompetition.service;

import com.guildup.pubg.exception.PubgApiException;
import com.guildup.pubg.model.*;
import com.guildup.pubg.service.PubgMatchService;
import com.guildup.pubg.service.PubgPlayerService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 선수 조회는 배치로, Match는 ID 중복 제거 후 한 번씩만 조회한다. */
@Service
public class KillCompetitionPubgAggregator {
    public record PlayerInput(Long participantId, String accountId) {}
    private final PubgPlayerService players;
    private final PubgMatchService matches;

    public KillCompetitionPubgAggregator(PubgPlayerService players, PubgMatchService matches) {
        this.players = players; this.matches = matches;
    }

    public KillCompetitionKillSnapshot aggregate(String shard, Instant startInclusive,
                                                  Instant endExclusive, List<PlayerInput> inputs) {
        Map<String, PlayerInput> inputByAccount = inputs.stream().collect(Collectors.toMap(
                PlayerInput::accountId, Function.identity(), (first, ignored) -> first, LinkedHashMap::new));
        List<PubgPlayer> loadedPlayers = players.findByAccountIdsFresh(shard, inputByAccount.keySet().stream().toList());
        Set<String> loadedIds = loadedPlayers.stream().map(PubgPlayer::accountId).collect(Collectors.toSet());
        if (!loadedIds.containsAll(inputByAccount.keySet())) {
            throw new PubgApiException("일부 참가자의 PUBG 계정을 조회하지 못해 정산을 중단했습니다.");
        }
        Set<String> matchIds = loadedPlayers.stream().flatMap(player -> player.matchIds().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, PubgMatch> loadedMatches = matches.findUniqueMatchesFresh(shard, matchIds);
        Map<Long, Integer> kills = new LinkedHashMap<>();
        Map<Long, Integer> counts = new LinkedHashMap<>();
        inputs.forEach(input -> { kills.put(input.participantId(), 0); counts.put(input.participantId(), 0); });
        List<KillCompetitionKillSnapshot.MatchKill> rows = new ArrayList<>();
        for (PubgMatch match : loadedMatches.values()) {
            if (match.playedAt() == null || match.playedAt().isBefore(startInclusive)
                    || !match.playedAt().isBefore(endExclusive)) continue;
            for (PubgParticipant player : match.teams().stream().flatMap(team -> team.participants().stream()).toList()) {
                PlayerInput input = inputByAccount.get(player.accountId());
                if (input == null) continue;
                kills.merge(input.participantId(), player.kills(), Integer::sum);
                counts.merge(input.participantId(), 1, Integer::sum);
                rows.add(new KillCompetitionKillSnapshot.MatchKill(
                        input.participantId(), match.matchId(), match.playedAt(), player.kills()));
            }
        }
        Map<Long, KillCompetitionKillSnapshot.PlayerTotal> totals = new LinkedHashMap<>();
        inputs.forEach(input -> totals.put(input.participantId(),
                new KillCompetitionKillSnapshot.PlayerTotal(kills.get(input.participantId()), counts.get(input.participantId()))));
        return new KillCompetitionKillSnapshot(Map.copyOf(totals), List.copyOf(rows));
    }
}
