package com.guildup.community.service;

import com.guildup.pubg.model.PubgPlatform;

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
import com.guildup.monitoring.logging.ActivitySyncLog;
import com.guildup.monitoring.logging.LogContext;
import com.guildup.monitoring.domain.MonitoringEventCode;
import com.guildup.pubg.service.PubgMatchService;
import com.guildup.pubg.service.PubgPlayerService;
import com.guildup.pubg.support.PubgGameSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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

    private static final Logger log = LoggerFactory.getLogger(CommunityMemberActivitySyncWorker.class);

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
    private final TransactionTemplate transactions;

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
            Clock clock,
            PlatformTransactionManager transactionManager
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
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public void synchronize(Long communityGameId, Instant attemptedAt) {
        long startedAtNanos = System.nanoTime();
        Long communityId = null;
        ActivitySyncLog operation = ActivitySyncLog.current();
        String stage = "PREPARATION";
        try {
            Preparation preparation = transactions.execute(status -> prepare(communityGameId, attemptedAt));
            if (preparation == null) {
                if (operation != null) operation.superseded();
                else log.info("Superseded activity task skipped - communityGameId={}, attemptAt={}", communityGameId, attemptedAt);
                return;
            }
            CommunityGame game = preparation.game();
            CommunityGameActivityRule rule = preparation.rule();
            List<CommunityMember> members = preparation.members();
            Map<Long, CommunityMember> membersById = preparation.membersById();
            Map<Long, CommunityMemberAccount> accountsByMemberId = preparation.accountsByMemberId();
            Map<Long, String> gameNicknames = preparation.gameNicknames();
            String shard = preparation.shard();
            communityId = preparation.communityId();

            int targetAccounts = (int) accountsByMemberId.values().stream()
                    .map(CommunityMemberAccount::getExternalUserId).distinct().count()
                    + (int) members.stream().filter(member -> !accountsByMemberId.containsKey(member.getId()))
                    .map(member -> gameNicknames.get(member.getId())).filter(java.util.Objects::nonNull).distinct().count();
            if (operation != null) {
                operation.enrich(Map.of("gameType", game.getGameType().name(), "communityName", preparation.communityName(),
                        "memberCount", members.size(), "targetAccounts", targetAccounts));
                LogContext.put("gameType", game.getGameType().name());
                operation.info(MonitoringEventCode.ACTIVITY_SYNC_STARTED, "인게임 활동 조회를 시작했습니다.", Map.of());
            }
            Map<String, PubgPlayer> playersByAccountId = new LinkedHashMap<>();
            stage = "PLAYER_FETCH";
            long phaseStarted = System.nanoTime();
            if (operation != null) {
                operation.stage(stage);
                operation.info(MonitoringEventCode.ACTIVITY_SYNC_PLAYER_FETCH_STARTED, "PUBG Player 조회 시작", Map.of());
            }
            resolveMissingAccounts(
                    shard, members, gameNicknames, accountsByMemberId, playersByAccountId
            );
            loadStoredPlayers(shard, accountsByMemberId, playersByAccountId);
            if (operation != null) {
                operation.enrich(Map.of("playersFound", playersByAccountId.size(),
                        "playersMissing", Math.max(0, targetAccounts - playersByAccountId.size())));
                var metrics = new LinkedHashMap<String, Object>(operation.playerMetrics());
                metrics.put("elapsedMs", elapsedMillis(phaseStarted));
                operation.info(MonitoringEventCode.ACTIVITY_SYNC_PLAYER_FETCH_COMPLETED, "PUBG Player 조회 완료", metrics);
            }

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
            stage = "MATCH_FETCH";
            phaseStarted = System.nanoTime();
            if (operation != null) {
                operation.stage(stage);
                operation.enrich(Map.of("uniqueMatches", matchIds.size(), "fetchConcurrency", pubgMatchService.getFetchConcurrency()));
                operation.info(MonitoringEventCode.ACTIVITY_SYNC_MATCH_FETCH_STARTED, "PUBG Match 조회 시작", Map.of());
            }
            Map<String, PubgMatch> matches = pubgMatchService.findUniqueMatchesFresh(
                    shard, matchIds, communityId, communityGameId
            );
            if (operation != null) {
                var metrics = new LinkedHashMap<String, Object>(operation.matchMetrics());
                metrics.put("successfulMatches", matches.size());
                metrics.put("missingMatches", matchIds.size() - matches.size());
                metrics.put("elapsedMs", elapsedMillis(phaseStarted));
                operation.info(MonitoringEventCode.ACTIVITY_SYNC_MATCH_FETCH_COMPLETED, "PUBG Match 조회 완료", metrics);
            }
            Instant synchronizedAt = clock.instant();
            Instant periodStart = synchronizedAt.minus(Duration.ofDays(rule.getActivityPeriodDays()));

            stage = "ACTIVITY_CALCULATION";
            phaseStarted = System.nanoTime();
            if (operation != null) {
                operation.stage(stage);
                operation.info(MonitoringEventCode.ACTIVITY_SYNC_CALCULATION_STARTED, "활동 스냅샷 계산 시작", Map.of());
            }
            List<CommunityMemberActivitySnapshot> activitySnapshots = members.stream()
                    .map(member -> createSnapshot(
                            game, member, gameNicknames, accountsByMemberId, playersByAccountId,
                            rule, periodStart, synchronizedAt, clanMembersByAccountId,
                            membersById, matches
                    ))
                    .toList();

            if (operation != null) operation.info(MonitoringEventCode.ACTIVITY_SYNC_CALCULATION_COMPLETED,
                    "활동 스냅샷 계산 완료", Map.of("plannedSnapshots", activitySnapshots.size(), "elapsedMs", elapsedMillis(phaseStarted)));
            stage = "DB_SAVE";
            phaseStarted = System.nanoTime();
            if (operation != null) {
                operation.stage(stage);
                operation.info(MonitoringEventCode.ACTIVITY_SYNC_SAVE_STARTED, "활동 스냅샷 저장 시작",
                        Map.of("plannedSnapshots", activitySnapshots.size()));
            }
            Boolean saved = transactions.execute(status -> {
                gameRepository.findByIdForUpdate(game.getId()).orElseThrow();
                var sync = syncRepository.findByCommunityGameId(game.getId()).orElseThrow();
                if (!sync.isCurrentAttempt(attemptedAt)) return false;
                accountRepository.saveAll(accountsByMemberId.values());
                snapshotRepository.deleteByCommunityGameId(game.getId());
                // 같은 (게임, 멤버) 키로 새 스냅샷을 넣기 전에 기존 DELETE를 먼저 실행한다.
                snapshotRepository.flush();
                snapshotRepository.saveAll(activitySnapshots);
                sync.succeed(synchronizedAt);
                snapshotRepository.flush();
                return true;
            });

            if (!Boolean.TRUE.equals(saved)) {
                if (operation != null) operation.superseded();
                else log.info("Superseded activity result discarded - communityGameId={}, attemptAt={}", communityGameId, attemptedAt);
                return;
            }

            if (operation != null) {
                operation.enrich(Map.of("snapshotCount", activitySnapshots.size(), "successChanged", true));
                operation.info(MonitoringEventCode.ACTIVITY_SYNC_SAVE_COMPLETED, "활동 스냅샷 저장 및 SUCCESS 변경 완료",
                        Map.of("elapsedMs", elapsedMillis(phaseStarted)));
                operation.completed();
            } else {
                log.info("PUBG activity sync completed - communityId={}, communityGameId={}, players={}, uniqueMatches={}, durationMs={}",
                        communityId, communityGameId, playersByAccountId.size(), matchIds.size(), elapsedMillis(startedAtNanos));
            }
        } catch (RuntimeException | Error exception) {
            if (operation != null) operation.stageFailed(exception);
            else log.error("PUBG activity sync failed - communityId={}, communityGameId={}, stage={}, durationMs={}",
                    communityId, communityGameId, stage, elapsedMillis(startedAtNanos), exception);
            throw exception;
        }
    }

    private Preparation prepare(Long communityGameId, Instant attemptedAt) {
        CommunityGame game = gameRepository.findByIdForUpdate(communityGameId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "배틀그라운드 게임 설정을 찾을 수 없습니다."
                ));
        if (!syncRepository.findByCommunityGameId(communityGameId).orElseThrow().isCurrentAttempt(attemptedAt)) {
            return null;
        }
        Long communityId = game.getCommunity().getId();
        CommunityGameActivityRule rule = activityRuleRepository.findByCommunityGameId(game.getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "클랜 활동 규칙을 먼저 설정해 주세요."
                ));
        CommunityGameNicknameRule nicknameRule = nicknameRuleRepository
                .findByCommunityIdAndGameType(communityId, game.getGameType())
                .orElse(null);
        NicknameRuleCandidate nicknameCandidate = nicknameRule == null ? null : toCandidate(nicknameRule);
        List<CommunityMember> members = memberRepository.findByCommunityIdAndStatusOrderByIdAsc(
                communityId, CommunityMemberStatus.ACTIVE
        );
        Map<Long, CommunityMember> membersById = members.stream()
                .collect(Collectors.toMap(CommunityMember::getId, Function.identity()));
        Map<Long, CommunityMemberAccount> accountsByMemberId = accountRepository
                .findByCommunityIdAndProviderAndPlatform(communityId, ExternalAccountProvider.PUBG, PubgGameSupport.requirePlatform(game)).stream()
                .collect(Collectors.toMap(
                        account -> account.getCommunityMember().getId(), Function.identity()
                ));
        Map<Long, String> gameNicknames = extractGameNicknames(members, accountsByMemberId, nicknameCandidate);
        String shard = PubgGameSupport.requireShard(game.getGameType());
        return new Preparation(
                communityId, game.getCommunity().getName(), game, rule, members, membersById, accountsByMemberId, gameNicknames, shard
        );
    }

    private long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
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
        Map<String, PubgPlayer> playersByName = pubgPlayerService.findByNamesFresh(shard, unresolvedNames).stream()
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
                    member, PubgPlatform.fromShard(shard), player.accountId(), player.name()
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
        for (PubgPlayer player : pubgPlayerService.findByAccountIdsFresh(shard, unloadedIds)) {
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

    private record Preparation(
            Long communityId,
            String communityName,
            CommunityGame game,
            CommunityGameActivityRule rule,
            List<CommunityMember> members,
            Map<Long, CommunityMember> membersById,
            Map<Long, CommunityMemberAccount> accountsByMemberId,
            Map<Long, String> gameNicknames,
            String shard
    ) {}
}
