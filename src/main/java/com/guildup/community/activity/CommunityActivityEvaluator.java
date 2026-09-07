package com.guildup.community.activity;

import com.guildup.community.domain.CommunityGameActivityRule;
import com.guildup.pubg.model.PubgMatch;
import com.guildup.pubg.model.PubgPlayer;
import com.guildup.pubg.model.PubgTeam;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 외부 API 통신과 분리된 순수한 같은 팀 클랜 활동 판정기다. */
@Component
public class CommunityActivityEvaluator {

    public ClanMemberActivityEvaluation evaluate(
            PubgPlayer player,
            CommunityGameActivityRule rule,
            Instant periodStart,
            Map<String, ClanMemberIdentity> clanMembersByAccountId,
            Map<String, PubgMatch> matchesById
    ) {
        List<ClanActivityMatch> matches = player.matchIds().stream()
                .distinct()
                .map(matchesById::get)
                .filter(match -> isWithinPeriod(match, periodStart))
                .map(match -> evaluateMatch(player.accountId(), match, rule, clanMembersByAccountId))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(ClanActivityMatch::playedAt).reversed())
                .toList();

        if (matches.isEmpty()) {
            return new ClanMemberActivityEvaluation(
                    ClanActivityStatus.NO_RECENT_MATCHES, null, List.of()
            );
        }
        Instant lastClanActivityAt = matches.stream()
                .filter(ClanActivityMatch::activityRecognized)
                .map(ClanActivityMatch::playedAt)
                .max(Comparator.naturalOrder())
                .orElse(null);
        return new ClanMemberActivityEvaluation(
                lastClanActivityAt == null
                        ? ClanActivityStatus.NO_CLAN_ACTIVITY
                        : ClanActivityStatus.ACTIVE,
                lastClanActivityAt,
                matches
        );
    }

    private boolean isWithinPeriod(PubgMatch match, Instant periodStart) {
        return match != null && match.playedAt() != null && !match.playedAt().isBefore(periodStart);
    }

    private Optional<ClanActivityMatch> evaluateMatch(
            String targetAccountId,
            PubgMatch match,
            CommunityGameActivityRule rule,
            Map<String, ClanMemberIdentity> clanMembersByAccountId
    ) {
        Optional<PubgTeam> targetTeam = match.teams().stream()
                .filter(team -> team.participants().stream()
                        .anyMatch(participant -> targetAccountId.equals(participant.accountId())))
                .findFirst();
        if (targetTeam.isEmpty()) return Optional.empty();

        List<String> clanAccountIdsInTeam = targetTeam.get().participants().stream()
                .map(participant -> participant.accountId())
                .filter(clanMembersByAccountId::containsKey)
                .distinct()
                .toList();
        List<String> otherClanMembers = clanAccountIdsInTeam.stream()
                .filter(accountId -> !targetAccountId.equals(accountId))
                .map(clanMembersByAccountId::get)
                .map(ClanMemberIdentity::displayName)
                .toList();
        List<ClanActivityPlayer> playersInTeam = targetTeam.get().participants().stream()
                .map(participant -> {
                    ClanMemberIdentity identity = clanMembersByAccountId.get(participant.accountId());
                    return new ClanActivityPlayer(
                            participant.accountId(),
                            participant.name(),
                            identity != null,
                            identity == null ? null : identity.memberId()
                    );
                })
                .toList();
        int clanMemberCount = clanAccountIdsInTeam.size();
        return Optional.of(new ClanActivityMatch(
                match.matchId(),
                match.playedAt(),
                match.gameMode(),
                clanMemberCount,
                otherClanMembers,
                playersInTeam,
                rule.isSatisfiedByClanMemberCountInRoster(clanMemberCount)
        ));
    }
}
