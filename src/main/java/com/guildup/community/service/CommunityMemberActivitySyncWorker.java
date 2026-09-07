package com.guildup.community.service;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.activity.ClanActivityStatus;
import com.guildup.community.activity.ClanMemberActivityEvaluation;
import com.guildup.community.activity.ClanMemberIdentity;
import com.guildup.community.activity.CommunityActivityEvaluator;
import com.guildup.community.domain.CommunityGame;
import com.guildup.community.domain.CommunityGameActivityRule;
import com.guildup.community.domain.CommunityGameNicknameRule;
import com.guildup.community.domain.CommunityMember;
import com.guildup.community.domain.CommunityMemberAccount;
import com.guildup.community.domain.CommunityMemberActivityMatch;
import com.guildup.community.domain.CommunityMemberActivityMatchPlayer;
import com.guildup.community.domain.CommunityMemberActivitySnapshot;
import com.guildup.community.domain.CommunityMemberStatus;
import com.guildup.community.repository.CommunityGameActivityRuleRepository;
import com.guildup.community.repository.CommunityGameActivitySyncRepository;
import com.guildup.community.repository.CommunityGameNicknameRuleRepository;
import com.guildup.community.repository.CommunityGameRepository;
import com.guildup.community.repository.CommunityMemberAccountRepository;
import com.guildup.community.repository.CommunityMemberActivitySnapshotRepository;
import com.guildup.community.repository.CommunityMemberRepository;
import com.guildup.community.service.nickname.GameNicknameRuleInferenceService;
import com.guildup.community.service.nickname.NicknameRuleCandidate;
import com.guildup.pubg.model.PubgMatch;
import com.guildup.pubg.model.PubgPlayer;
import com.guildup.pubg.service.PubgMatchService;
import com.guildup.pubg.service.PubgPlayerService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CommunityMemberActivitySyncWorker {

    private final CommunityGameRepository gameRepository;
    private final CommunityGameActivityRuleRepository activityRuleRepository;
    private final CommunityGameActivitySyncRepository syncRepository;
    private final CommunityGameNicknameRuleRepository nicknameRuleRepository;
    private final CommunityMemberRepository memberRepository;
    private final CommunityMemberAccountRepository accountRepository;
    private final CommunityMemberActivitySnapshotRepository snapshotRepository;
    private final GameNicknameRuleInferenceService nicknameInferenceService;
    private final PubgPlayerService pubgPlayerService;
    private final PubgMatchService pubgMatchService;
    private final CommunityActivityEvaluator evaluator;
    private final Clock clock;

    public CommunityMemberActivitySyncWorker(
            CommunityGameRepository gameRepository,
            CommunityGameActivityRuleRepository activityRuleRepository,
            CommunityGameActivitySyncRepository syncRepository,
            CommunityGameNicknameRuleRepository nicknameRuleRepository,
            CommunityMemberRepository memberRepository,
            CommunityMemberAccountRepository accountRepository,
            CommunityMemberActivitySnapshotRepository snapshotRepository,
            GameNicknameRuleInferenceService nicknameInferenceService,
            PubgPlayerService pubgPlayerService,
            PubgMatchService pubgMatchService,
            CommunityActivityEvaluator evaluator,
            Clock clock
    ) {
        this.gameRepository = gameRepository;
        this.activityRuleRepository = activityRuleRepository;
        this.syncRepository = syncRepository;
        this.nicknameRuleRepository = nicknameRuleRepository;
        this.memberRepository = memberRepository;
        this.accountRepository = accountRepository;
        this.snapshotRepository = snapshotRepository;
        this.nicknameInferenceService = nicknameInferenceService;
        this.pubgPlayerService = pubgPlayerService;
        this.pubgMatchService = pubgMatchService;
        this.evaluator = evaluator;
        this.clock = clock;
    }

    @Transactional
    public void synchronize(Long communityGameId) {
        CommunityGame game = gameRepository.findById(communityGameId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "배틀그라운드 게임 설정을 찾을 수 없습니다."
                ));
        CommunityGameActivityRule rule = activityRuleRepository.findByCommunityGameId(game.getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "클랜 활동 규칙을 먼저 설정해 주세요."
                ));
        CommunityGameNicknameRule nicknameRule = nicknameRuleRepository
                .findByCommunityIdAndGameType(game.getCommunity().getId(), game.getGameType())
                .orElse(null);
        NicknameRuleCandidate nicknameCandidate = nicknameRule == null ? null : toCandidate(nicknameRule);
        List<CommunityMember> members = memberRepository.findByCommunityIdAndStatusOrderByIdAsc(
                game.getCommunity().getId(), CommunityMemberStatus.ACTIVE
        );
        Map<Long, CommunityMember> membersById = members.stream()
                .collect(Collectors.toMap(CommunityMember::getId, Function.identity()));
        Map<Long, CommunityMemberAccount> accountsByMemberId = accountRepository
                .findByCommunityIdAndProvider(
                        game.getCommunity().getId(), ExternalAccountProvider.PUBG
                ).stream()
                .collect(Collectors.toMap(
                        account -> account.getCommunityMember().getId(), Function.identity()
                ));
        Map<Long, String> gameNicknames = extractGameNicknames(
                members, accountsByMemberId, nicknameCandidate
        );

        Map<String, PubgPlayer> playersByAccountId = new LinkedHashMap<>();
        List<CommunityMemberAccount> newAccounts = resolveMissingAccounts(
                game.getGameType().getPubgShard(), members, gameNicknames,
                accountsByMemberId, playersByAccountId
        );
        loadStoredPlayers(
                game.getGameType().getPubgShard(), accountsByMemberId, playersByAccountId
        );

        Map<String, ClanMemberIdentity> clanMembersByAccountId = accountsByMemberId.values().stream()
                .collect(Collectors.toMap(
                        CommunityMemberAccount::getExternalUserId,
                        account -> new ClanMemberIdentity(
                                account.getCommunityMember().getId(),
                                account.getCommunityMember().getNickname()
                        ),
                        (first, ignored) -> first,
                        LinkedHashMap::new
                ));
        List<String> matchIds = playersByAccountId.values().stream()
                .flatMap(player -> player.matchIds().stream())
                .distinct()
                .toList();
        Map<String, PubgMatch> matches = pubgMatchService.findUniqueMatches(
                game.getGameType().getPubgShard(), matchIds
        );
        Instant synchronizedAt = clock.instant();
        Instant periodStart = synchronizedAt.minus(Duration.ofDays(rule.getActivityPeriodDays()));

        List<CommunityMemberActivitySnapshot> snapshots = members.stream()
                .map(member -> createSnapshot(
                        game, member, gameNicknames, accountsByMemberId, playersByAccountId,
                        rule, periodStart, synchronizedAt, clanMembersByAccountId,
                        membersById, matches
                ))
                .toList();

        if (!newAccounts.isEmpty()) accountRepository.saveAll(newAccounts);
        snapshotRepository.deleteByCommunityGameId(game.getId());
        // 같은 (게임, 멤버) 키로 새 스냅샷을 넣기 전에 기존 DELETE를 먼저 실행한다.
        snapshotRepository.flush();
        snapshotRepository.saveAll(snapshots);
        syncRepository.findByCommunityGameId(game.getId())
                .orElseThrow()
                .succeed(synchronizedAt);
    }

    private CommunityMemberActivitySnapshot createSnapshot(
            CommunityGame game,
            CommunityMember member,
            Map<Long, String> gameNicknames,
            Map<Long, CommunityMemberAccount> accountsByMemberId,
            Map<String, PubgPlayer> playersByAccountId,
            CommunityGameActivityRule rule,
            Instant periodStart,
            Instant synchronizedAt,
            Map<String, ClanMemberIdentity> clanMembersByAccountId,
            Map<Long, CommunityMember> membersById,
            Map<String, PubgMatch> matches
    ) {
        CommunityMemberAccount account = accountsByMemberId.get(member.getId());
        PubgPlayer player = account == null ? null : playersByAccountId.get(account.getExternalUserId());
        String gameNickname = account != null && account.getExternalUsername() != null
                ? account.getExternalUsername() : gameNicknames.get(member.getId());
        ClanMemberActivityEvaluation evaluation = player == null ? null : evaluator.evaluate(
                player, rule, periodStart, clanMembersByAccountId, matches
        );
        Instant lastPubgMatchAt = player == null ? null : player.matchIds().stream()
                .map(matches::get)
                .filter(java.util.Objects::nonNull)
                .map(PubgMatch::playedAt)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        CommunityMemberActivitySnapshot snapshot = new CommunityMemberActivitySnapshot(
                game,
                member,
                account == null ? null : account.getExternalUserId(),
                gameNickname,
                evaluation == null
                        ? ClanActivityStatus.ACCOUNT_VERIFICATION_REQUIRED
                        : evaluation.status(),
                lastPubgMatchAt,
                evaluation == null ? null : evaluation.lastClanActivityAt(),
                synchronizedAt
        );
        if (evaluation != null) {
            evaluation.matches().forEach(match -> {
                CommunityMemberActivityMatch storedMatch = new CommunityMemberActivityMatch(
                        snapshot, match.matchId(), match.playedAt(), match.gameMode(),
                        match.activityRecognized(), match.clanMemberCountInTeam()
                );
                match.playersInTeam().forEach(playerInTeam -> storedMatch.addPlayer(
                        new CommunityMemberActivityMatchPlayer(
                                storedMatch,
                                playerInTeam.accountId(),
                                playerInTeam.nickname(),
                                membersById.get(playerInTeam.communityMemberId())
                        )
                ));
                snapshot.addMatch(storedMatch);
            });
        }
        return snapshot;
    }

    private Map<Long, String> extractGameNicknames(
            List<CommunityMember> members,
            Map<Long, CommunityMemberAccount> accountsByMemberId,
            NicknameRuleCandidate nicknameCandidate
    ) {
        Map<Long, String> nicknames = new LinkedHashMap<>();
        for (CommunityMember member : members) {
            CommunityMemberAccount account = accountsByMemberId.get(member.getId());
            String storedName = account == null ? null : account.getExternalUsername();
            if (storedName != null && !storedName.isBlank()) {
                nicknames.put(member.getId(), storedName);
            } else if (nicknameCandidate != null) {
                nicknameInferenceService.extract(nicknameCandidate, member.getNickname())
                        .ifPresent(nickname -> nicknames.put(member.getId(), nickname));
            }
        }
        return nicknames;
    }

    private List<CommunityMemberAccount> resolveMissingAccounts(
            String shard,
            List<CommunityMember> members,
            Map<Long, String> gameNicknames,
            Map<Long, CommunityMemberAccount> accountsByMemberId,
            Map<String, PubgPlayer> playersByAccountId
    ) {
        List<String> unresolvedNames = members.stream()
                .filter(member -> !accountsByMemberId.containsKey(member.getId()))
                .map(member -> gameNicknames.get(member.getId()))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<String, PubgPlayer> playersByName = pubgPlayerService.findByNames(shard, unresolvedNames).stream()
                .filter(player -> player.name() != null && player.accountId() != null)
                .collect(Collectors.toMap(
                        player -> normalize(player.name()), Function.identity(),
                        (first, ignored) -> first, LinkedHashMap::new
                ));
        Set<String> claimedAccountIds = accountsByMemberId.values().stream()
                .map(CommunityMemberAccount::getExternalUserId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<CommunityMemberAccount> newAccounts = new ArrayList<>();
        for (CommunityMember member : members) {
            if (accountsByMemberId.containsKey(member.getId())) continue;
            String nickname = gameNicknames.get(member.getId());
            PubgPlayer player = nickname == null ? null : playersByName.get(normalize(nickname));
            if (player == null || !claimedAccountIds.add(player.accountId())) continue;
            CommunityMemberAccount account = new CommunityMemberAccount(
                    member, ExternalAccountProvider.PUBG, player.accountId(), player.name()
            );
            accountsByMemberId.put(member.getId(), account);
            playersByAccountId.put(player.accountId(), player);
            newAccounts.add(account);
        }
        return newAccounts;
    }

    private void loadStoredPlayers(
            String shard,
            Map<Long, CommunityMemberAccount> accountsByMemberId,
            Map<String, PubgPlayer> playersByAccountId
    ) {
        List<String> unloadedIds = accountsByMemberId.values().stream()
                .map(CommunityMemberAccount::getExternalUserId)
                .filter(accountId -> !playersByAccountId.containsKey(accountId))
                .distinct()
                .toList();
        for (PubgPlayer player : pubgPlayerService.findByAccountIds(shard, unloadedIds)) {
            if (player.accountId() != null) playersByAccountId.put(player.accountId(), player);
        }
        for (CommunityMemberAccount account : accountsByMemberId.values()) {
            PubgPlayer player = playersByAccountId.get(account.getExternalUserId());
            if (player != null && player.name() != null) account.updateExternalUsername(player.name());
        }
    }

    private NicknameRuleCandidate toCandidate(CommunityGameNicknameRule rule) {
        return new NicknameRuleCandidate(
                rule.getStrategyType(), rule.getDelimiterType(), rule.getSegmentIndex(),
                rule.isFromEnd(), rule.getExpectedSegmentCount()
        );
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
