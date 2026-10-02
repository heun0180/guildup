package com.guildup.bingo.service;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.bingo.domain.*;
import com.guildup.bingo.dto.TemporaryBingoRebuildResponse;
import com.guildup.bingo.mission.*;
import com.guildup.bingo.repository.*;
import com.guildup.community.domain.*;
import com.guildup.community.repository.CommunityMemberAccountRepository;
import com.guildup.community.service.CommunityGameAccessService;
import com.guildup.killcompetition.domain.*;
import com.guildup.killcompetition.repository.KillCompetitionRepository;
import com.guildup.killcompetition.service.KillCompetitionWinnerResolver;
import com.guildup.monitoring.domain.MonitoringCategory;
import com.guildup.monitoring.domain.MonitoringEventCode;
import com.guildup.monitoring.service.MonitoringEventService;
import com.guildup.pubg.model.*;
import com.guildup.pubg.service.*;
import com.guildup.pubg.support.PubgGameSupport;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.guildup.pubg.exception.PubgApiException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * TEMPORARY: 2026-09 bingo progress repair tool.
 * Remove after current event verification.
 *
 * 일반 증분 집계와 분리해 원본 조회를 모두 성공시킨 뒤에만 완성된 snapshot을 적용한다.
 */
@Service
public class TemporaryBingoRebuildService {
    private static final Logger log = LoggerFactory.getLogger(TemporaryBingoRebuildService.class);
    private static final Duration PREVIEW_TTL = Duration.ofMinutes(30);
    private static final Set<BingoMissionType> TELEMETRY_REQUIRED = EnumSet.of(
            BingoMissionType.LONG_DISTANCE_KILL, BingoMissionType.WEAPON_KILLS,
            BingoMissionType.WEAPON_CATEGORY_KILLS, BingoMissionType.THROWABLE_KILLS,
            BingoMissionType.WALL_PENETRATION_KILLS, BingoMissionType.REVIVES,
            BingoMissionType.WALK_DISTANCE, BingoMissionType.RIDE_DISTANCE);

    private final BingoEventRepository events;
    private final BingoParticipantRepository participants;
    private final BingoProgressRepository progress;
    private final BingoProcessedMatchRepository processedMatches;
    private final BingoLineCompletionRepository lineCompletions;
    private final CommunityMemberAccountRepository memberAccounts;
    private final KillCompetitionRepository competitions;
    private final KillCompetitionWinnerResolver winnerResolver;
    private final PubgPlayerService players;
    private final PubgMatchService matches;
    private final PubgBingoFactService facts;
    private final BingoMissionEngine missions;
    private final BingoMatchPolicy matchPolicy;
    private final BingoLineCalculator lineCalculator;
    private final CommunityGameAccessService gameAccess;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private MonitoringEventService monitoring;
    private final ConcurrentMap<Long, ReentrantLock> eventLocks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PreviewSnapshot> previews = new ConcurrentHashMap<>();

    public TemporaryBingoRebuildService(
            BingoEventRepository events, BingoParticipantRepository participants,
            BingoProgressRepository progress, BingoProcessedMatchRepository processedMatches,
            BingoLineCompletionRepository lineCompletions,
            CommunityMemberAccountRepository memberAccounts,
            KillCompetitionRepository competitions, KillCompetitionWinnerResolver winnerResolver,
            PubgPlayerService players,
            PubgMatchService matches, PubgBingoFactService facts, BingoMissionEngine missions,
            BingoMatchPolicy matchPolicy, BingoLineCalculator lineCalculator,
            CommunityGameAccessService gameAccess,
            org.springframework.transaction.PlatformTransactionManager transactionManager, Clock clock) {
        this.events = events; this.participants = participants; this.progress = progress;
        this.processedMatches = processedMatches; this.lineCompletions = lineCompletions;
        this.memberAccounts = memberAccounts; this.competitions = competitions; this.winnerResolver = winnerResolver;
        this.players = players; this.matches = matches; this.facts = facts; this.missions = missions;
        this.matchPolicy = matchPolicy; this.lineCalculator = lineCalculator; this.gameAccess = gameAccess;
        this.transactions = new TransactionTemplate(transactionManager); this.clock = clock;
    }

    @Autowired
    void configureMonitoring(MonitoringEventService monitoring) { this.monitoring = monitoring; }

    public TemporaryBingoRebuildResponse preview(Long userId, Long communityId, Long communityGameId) {
        CommunityGame game = gameAccess.requireManageable(userId, communityId, communityGameId, GameCapability.BINGO);
        BingoEvent event = requireOnlyActive(communityId, communityGameId);
        ReentrantLock lock = eventLocks.computeIfAbsent(event.getId(), ignored -> new ReentrantLock());
        if (!lock.tryLock()) conflict("REBUILD_IN_PROGRESS");
        try {
            removeExpiredPreviews();
            BuildResult result = calculate(event, communityId, communityGameId,
                    PubgGameSupport.requirePlatform(game));
            if (!result.failures().isEmpty()) return failed(event, userId, communityId, communityGameId, "PREVIEW", result);
            String token = UUID.randomUUID().toString();
            PreviewSnapshot snapshot = result.snapshot(token, clock.instant().plus(PREVIEW_TTL));
            previews.put(token, snapshot);
            return response("REBUILD_PREVIEW_READY", token, snapshot);
        } finally {
            lock.unlock();
        }
    }

    public TemporaryBingoRebuildResponse apply(Long userId, Long communityId, Long communityGameId,
                                                String previewToken) {
        CommunityGame game = gameAccess.requireManageable(
                userId, communityId, communityGameId, GameCapability.BINGO);
        PreviewSnapshot snapshot = previews.get(previewToken);
        if (snapshot == null || !snapshot.expiresAt().isAfter(clock.instant()))
            conflict("재집계 미리보기가 없거나 만료되었습니다. 다시 미리보기를 실행해 주세요.");
        if (!snapshot.communityId().equals(communityId) || !snapshot.communityGameId().equals(communityGameId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "다른 커뮤니티의 미리보기는 적용할 수 없습니다.");
        ReentrantLock lock = eventLocks.computeIfAbsent(snapshot.eventId(), ignored -> new ReentrantLock());
        if (!lock.tryLock()) conflict("REBUILD_IN_PROGRESS");
        try {
            BingoEvent active = requireOnlyActive(communityId, communityGameId);
            BuildResult recalculated = calculate(active, communityId, communityGameId,
                    PubgGameSupport.requirePlatform(game));
            if (!recalculated.failures().isEmpty()) return failed(active, userId, communityId, communityGameId, "APPLY", recalculated);
            PreviewSnapshot current = recalculated.snapshot(snapshot.token(), snapshot.expiresAt());
            if (!sameRepairPlan(snapshot, current))
                conflict("미리보기 이후 원본 경기 또는 계산 결과가 변경되었습니다. 다시 검증해 주세요.");
            TemporaryBingoRebuildResponse applied = transactions.execute(status -> applySnapshot(current));
            previews.remove(previewToken);
            return applied;
        } finally {
            lock.unlock();
        }
    }

    private BuildResult calculate(BingoEvent event, Long communityId, Long communityGameId, PubgPlatform platform) {
        String shard = platform.getShard();
        Instant calculatedAt = clock.instant();
        List<BingoParticipant> participantRows = participants.findByEventIdOrderByIdAsc(event.getId());
        List<BingoCell> cells = event.getCells();
        if (cells.size() != 16)
            conflict("현재 활성 빙고가 16개 셀로 구성되어 있지 않습니다.");

        List<CommunityMemberAccount> currentAccounts = memberAccounts.findByCommunityIdAndProviderAndPlatform(
                communityId, ExternalAccountProvider.PUBG, platform);
        Map<Long, CommunityMemberAccount> accountByMember = currentAccounts.stream().collect(Collectors.toMap(
                account -> account.getCommunityMember().getId(), Function.identity(), (left, right) -> left));
        List<TemporaryBingoRebuildResponse.Failure> failures = new ArrayList<>();
        Set<FailureDiagnostic> diagnostics = new LinkedHashSet<>();
        Map<Long, AccountSnapshot> accounts = new LinkedHashMap<>();
        for (BingoParticipant participant : participantRows) {
            CommunityMember member = participant.getCommunityMember();
            CommunityMemberAccount account = member == null ? null : accountByMember.get(member.getId());
            if (account == null || blank(account.getExternalUserId())) {
                diagnostics.add(diagnostic("PARTICIPANT_ACCOUNT", null));
                failures.add(failure(participant, null, "현재 연결된 PUBG accountId가 없습니다."));
            } else {
                accounts.put(participant.getId(), new AccountSnapshot(account.getExternalUserId(),
                        account.getExternalUsername(), member.getId(), participant.getEligibleFrom()));
            }
        }
        if (!failures.isEmpty()) return new BuildResult(null, failures, diagnostics);

        List<PubgPlayer> loadedPlayers;
        try {
            loadedPlayers = players.findByAccountIdsFresh(shard,
                    accounts.values().stream().map(AccountSnapshot::accountId).toList());
        } catch (RuntimeException exception) {
            diagnostics.add(diagnostic("PLAYER_FETCH", exception));
            log.error("Bingo rebuild failed - bingoEventId={} communityId={} stage=PLAYER_FETCH participantCount={}",
                    event.getId(), communityId, participantRows.size(), exception);
            participantRows.forEach(participant -> failures.add(failure(participant, null,
                    "Player API 조회 실패: " + reason(exception))));
            return new BuildResult(null, failures, diagnostics);
        }
        Map<String, PubgPlayer> playerByAccount = loadedPlayers.stream().collect(Collectors.toMap(
                PubgPlayer::accountId, Function.identity(), (left, right) -> left));
        Map<Long, Set<String>> candidates = new LinkedHashMap<>();
        Map<Long, Set<String>> processedByParticipant = new LinkedHashMap<>();
        for (BingoParticipant participant : participantRows) {
            AccountSnapshot account = accounts.get(participant.getId());
            PubgPlayer player = playerByAccount.get(account.accountId());
            if (player == null) {
                diagnostics.add(diagnostic("PLAYER_RESPONSE", null));
                failures.add(failure(participant, null, "Player API 응답에 현재 accountId가 없습니다."));
                continue;
            }
            Set<String> processedIds = new LinkedHashSet<>(
                    processedMatches.findMatchIds(event.getId(), participant.getId()));
            processedByParticipant.put(participant.getId(), processedIds);
            Set<String> union = new LinkedHashSet<>(processedIds);
            union.addAll(player.matchIds());
            candidates.put(participant.getId(), union);
        }
        if (!failures.isEmpty()) return new BuildResult(null, failures, diagnostics);

        Map<String, Set<Long>> participantIdsByMatch = new LinkedHashMap<>();
        candidates.forEach((participantId, ids) -> ids.forEach(matchId ->
                participantIdsByMatch.computeIfAbsent(matchId, ignored -> new LinkedHashSet<>()).add(participantId)));
        Map<String, PubgMatch> loadedMatches = new LinkedHashMap<>();
        for (Map.Entry<String, Set<Long>> entry : participantIdsByMatch.entrySet()) {
            try {
                PubgMatch match = matches.findUniqueMatchesFresh(shard, List.of(entry.getKey())).get(entry.getKey());
                if (match == null) {
                    diagnostics.add(diagnostic("MATCH_RESPONSE", null));
                    entry.getValue().forEach(id -> failures.add(failure(participant(id, participantRows),
                            entry.getKey(), "Match 원본을 다시 조회할 수 없습니다.")));
                } else loadedMatches.put(entry.getKey(), match);
            } catch (RuntimeException exception) {
                diagnostics.add(diagnostic("MATCH_FETCH", exception));
                log.error("Bingo rebuild failed - bingoEventId={} communityId={} stage=MATCH_FETCH matchId={}",
                        event.getId(), communityId, entry.getKey(), exception);
                entry.getValue().forEach(id -> failures.add(failure(participant(id, participantRows),
                        entry.getKey(), "Match API 조회 실패: " + reason(exception))));
            }
        }
        if (!failures.isEmpty()) return new BuildResult(null, failures, diagnostics);

        Set<String> communityAccounts = currentAccounts.stream()
                .filter(account -> account.getCommunityMember().getStatus() == CommunityMemberStatus.ACTIVE)
                .map(CommunityMemberAccount::getExternalUserId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        communityAccounts.addAll(accounts.values().stream().map(AccountSnapshot::accountId).toList());
        boolean requireTelemetry = event.isExcludeBotCombatStats()
                || cells.stream().map(BingoCell::getMissionType).anyMatch(TELEMETRY_REQUIRED::contains);

        Map<Long, Map<Long, Accumulator>> accumulators = new LinkedHashMap<>();
        participantRows.forEach(participant -> {
            Map<Long, Accumulator> byCell = new LinkedHashMap<>();
            cells.forEach(cell -> byCell.put(cell.getId(), new Accumulator()));
            accumulators.put(participant.getId(), byCell);
        });
        Set<String> countedMatches = new LinkedHashSet<>();
        List<PubgMatch> orderedMatches = loadedMatches.values().stream()
                .sorted(Comparator.comparing(PubgMatch::playedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(PubgMatch::matchId)).toList();
        for (PubgMatch match : orderedMatches) {
            if (match.playedAt() == null || match.playedAt().isBefore(event.getStartsAt())
                    || !match.playedAt().isBefore(event.getMatchStartUpperBoundExclusive())
                    || !matchPolicy.isEligible(match)) continue;
            Set<Long> eligibleParticipants = participantIdsByMatch.getOrDefault(match.matchId(), Set.of()).stream()
                    .filter(id -> !match.playedAt().isBefore(accounts.get(id).eligibleFrom())).collect(Collectors.toSet());
            if (eligibleParticipants.isEmpty()) continue;
            Set<Long> notProcessed = eligibleParticipants.stream()
                    .filter(id -> !processedByParticipant.getOrDefault(id, Set.of()).contains(match.matchId()))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (!notProcessed.isEmpty()) {
                diagnostics.add(diagnostic("PROCESSED_MATCH_VALIDATION", null));
                notProcessed.forEach(id -> failures.add(failure(participant(id, participantRows), match.matchId(),
                        "아직 일반 집계되지 않은 최신 Match입니다. 일반 집계를 먼저 실행한 뒤 다시 검증해 주세요.")));
                continue;
            }
            Map<String, PlayerMatchFacts> matchFacts;
            try {
                matchFacts = requireTelemetry ? facts.factsRequired(platform, match, communityAccounts)
                        : facts.facts(platform, match, communityAccounts);
            } catch (RuntimeException exception) {
                String stage = telemetryFailureStage(exception);
                diagnostics.add(diagnostic(stage, exception));
                log.error("Bingo rebuild failed - bingoEventId={} communityId={} stage={} matchId={}",
                        event.getId(), communityId, stage, match.matchId(), exception);
                eligibleParticipants.forEach(id -> failures.add(failure(participant(id, participantRows), match.matchId(),
                        "Telemetry 조회 실패: " + reason(exception))));
                continue;
            }
            for (Long participantId : eligibleParticipants) {
                AccountSnapshot account = accounts.get(participantId);
                PlayerMatchFacts playerFacts = matchFacts.get(account.accountId());
                if (playerFacts == null) {
                    diagnostics.add(diagnostic("PARTICIPANT_FACT", null));
                    failures.add(failure(participant(participantId, participantRows), match.matchId(),
                            "Match 참가자 원본에 현재 accountId가 없습니다."));
                    continue;
                }
                for (BingoCell cell : cells) {
                    if (cell.getMissionType().source() != BingoMissionSource.PUBG_MATCH) continue;
                    advance(accumulators.get(participantId).get(cell.getId()), cell, playerFacts,
                            event.isExcludeBotCombatStats(), event.isClanPlayRequired(),
                            match.matchId(), playerFacts.latestEvidenceAt() == null ? match.playedAt() : playerFacts.latestEvidenceAt());
                }
                countedMatches.add(match.matchId());
            }
        }
        if (!failures.isEmpty()) return new BuildResult(null, failures, diagnostics);

        List<KillCompetition> relevantCompetitions = competitions.findByCommunityGameIdOrderByCreatedAtDesc(
                        communityGameId).stream()
                .filter(competition -> competition.getStatus() == KillCompetitionStatus.COMPLETED)
                .filter(competition -> competition.getCommunity().getId().equals(communityId))
                .filter(competition -> !competition.getEndsAt().isBefore(event.getStartsAt())
                        && competition.getEndsAt().isBefore(event.getMatchStartUpperBoundExclusive()))
                .sorted(Comparator.comparing(KillCompetition::getEndsAt).thenComparing(KillCompetition::getId)).toList();
        List<String> competitionFingerprint = new ArrayList<>();
        for (KillCompetition competition : relevantCompetitions) {
            Set<Long> winnerMemberIds = winnerMemberIds(competition);
            competitionFingerprint.add(competition.getId() + ":" + competition.getEndsAt() + ":" + winnerMemberIds);
            for (BingoParticipant participant : participantRows) {
                AccountSnapshot account = accounts.get(participant.getId());
                if (!winnerMemberIds.contains(account.memberId())
                        || competition.getEndsAt().isBefore(account.eligibleFrom())) continue;
                PlayerMatchFacts contentFact = contentFact(competition);
                for (BingoCell cell : cells) {
                    if (cell.getMissionType() != BingoMissionType.KILL_BET_WIN) continue;
                    advance(accumulators.get(participant.getId()).get(cell.getId()), cell, contentFact, false, false,
                            null, competition.getEndsAt());
                }
            }
        }

        List<ParticipantSnapshot> snapshots = new ArrayList<>();
        int changed = 0;
        for (BingoParticipant participant : participantRows) {
            Map<Long, BingoProgress> oldByCell = progress.findByParticipantIdOrderByCellPositionAsc(participant.getId())
                    .stream().collect(Collectors.toMap(row -> row.getCell().getId(), Function.identity()));
            if (oldByCell.size() != cells.size()) conflict("참가자의 진행도 셀 구성이 현재 빙고와 일치하지 않습니다.");
            List<ProgressSnapshot> values = new ArrayList<>();
            for (BingoCell cell : cells) {
                BingoProgress old = oldByCell.get(cell.getId());
                Accumulator next = accumulators.get(participant.getId()).get(cell.getId());
                ProgressSnapshot value = new ProgressSnapshot(old.getId(), cell.getId(), cell.getMissionType(), title(cell),
                        old.getCurrentValue(), old.getOccurrenceCount(), old.isCompleted(), old.getCompletedAt(),
                        next.outcome.value(), next.outcome.occurrences(), next.outcome.completed(),
                        next.completedAt, next.evidenceMatchId, next.evidenceAt);
                if (value.changed()) changed++;
                values.add(value);
            }
            snapshots.add(new ParticipantSnapshot(participant.getId(), accounts.get(participant.getId()), values));
        }
        PreviewSnapshot snapshot = new PreviewSnapshot(null, event.getId(), communityId,
                communityGameId, event.getTitle(), calculatedAt, null,
                event.getLastAggregatedAt(), eventFingerprint(event), List.copyOf(competitionFingerprint),
                snapshots, countedMatches.size(), changed);
        return new BuildResult(snapshot, List.of(), Set.of());
    }

    private TemporaryBingoRebuildResponse applySnapshot(PreviewSnapshot snapshot) {
        BingoEvent event = events.findForUpdate(snapshot.eventId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.CONFLICT, "활성 빙고가 사라졌습니다."));
        BingoEvent active = requireOnlyActive(snapshot.communityId(), snapshot.communityGameId());
        if (!active.getId().equals(event.getId())) conflict("활성 빙고가 미리보기 이후 변경되었습니다.");
        if (!Objects.equals(snapshot.lastAggregatedAt(), event.getLastAggregatedAt())
                || !Objects.equals(snapshot.cellFingerprint(), eventFingerprint(event)))
            conflict("미리보기 이후 빙고 또는 일반 집계 데이터가 변경되었습니다. 다시 미리보기를 실행해 주세요.");

        Map<Long, CommunityMemberAccount> currentAccounts = memberAccounts.findByCommunityIdAndProviderAndPlatform(
                        snapshot.communityId(), ExternalAccountProvider.PUBG, PubgGameSupport.requirePlatform(event.getCommunityGame())).stream()
                .collect(Collectors.toMap(account -> account.getCommunityMember().getId(), Function.identity(), (a, b) -> a));
        Map<Long, BingoParticipant> currentParticipants = participants.findByEventIdOrderByIdAsc(event.getId()).stream()
                .collect(Collectors.toMap(BingoParticipant::getId, Function.identity()));
        if (currentParticipants.size() != snapshot.participants().size()) conflict("미리보기 이후 참가자 구성이 변경되었습니다.");

        for (ParticipantSnapshot participantSnapshot : snapshot.participants()) {
            BingoParticipant participant = currentParticipants.get(participantSnapshot.participantId());
            if (participant == null) conflict("미리보기 이후 참가자가 변경되었습니다.");
            CommunityMemberAccount currentAccount = currentAccounts.get(participantSnapshot.account().memberId());
            if (currentAccount == null || !Objects.equals(currentAccount.getExternalUserId(), participantSnapshot.account().accountId()))
                conflict("미리보기 이후 PUBG 계정 연결이 변경되었습니다.");
            Map<Long, BingoProgress> currentById = progress.findByParticipantIdOrderByCellPositionAsc(participant.getId())
                    .stream().collect(Collectors.toMap(BingoProgress::getId, Function.identity()));
            for (ProgressSnapshot value : participantSnapshot.progress()) {
                BingoProgress row = currentById.get(value.progressId());
                if (row == null || !value.matchesOriginal(row))
                    conflict("미리보기 이후 진행도가 변경되었습니다. 다시 미리보기를 실행해 주세요.");
            }
        }

        List<String> currentCompetitionFingerprint = competitionFingerprint(
                event, snapshot.communityId(), snapshot.communityGameId());
        if (!Objects.equals(currentCompetitionFingerprint, snapshot.competitionFingerprint()))
            conflict("미리보기 이후 킬내기 결과가 변경되었습니다. 다시 미리보기를 실행해 주세요.");

        Instant now = clock.instant();
        for (ParticipantSnapshot participantSnapshot : snapshot.participants()) {
            BingoParticipant participant = currentParticipants.get(participantSnapshot.participantId());
            Map<Long, BingoProgress> rows = progress.findByParticipantIdOrderByCellPositionAsc(participant.getId())
                    .stream().collect(Collectors.toMap(BingoProgress::getId, Function.identity()));
            participantSnapshot.progress().stream().filter(ProgressSnapshot::changed).forEach(value ->
                    rows.get(value.progressId()).replaceSnapshot(
                            value.recomputedValue(), value.recomputedOccurrences(), value.recomputedCompleted(),
                            value.recomputedCompletedAt(), value.evidenceMatchId(), value.evidenceAt(), now));
            rebuildLines(event, participant, participantSnapshot.progress(), now);
        }
        return response("REBUILD_APPLIED", snapshot.token(), snapshot);
    }

    private void rebuildLines(BingoEvent event, BingoParticipant participant,
                              List<ProgressSnapshot> values, Instant fallback) {
        Map<Integer, Instant> completedAtByPosition = new HashMap<>();
        Map<Long, Integer> positions = event.getCells().stream().collect(Collectors.toMap(BingoCell::getId, BingoCell::getPosition));
        values.stream().filter(ProgressSnapshot::recomputedCompleted).forEach(value -> completedAtByPosition.put(
                positions.get(value.cellId()), Optional.ofNullable(value.recomputedCompletedAt()).orElse(fallback)));
        Set<String> completed = lineCalculator.completedLines(event.getBoardSize(), completedAtByPosition.keySet());
        Map<String, Instant> expectedLines = new LinkedHashMap<>();
        for (String key : completed) {
            Instant completedAt = linePositions(key, event.getBoardSize()).stream()
                    .map(completedAtByPosition::get).max(Comparator.naturalOrder()).orElse(fallback);
            expectedLines.put(key, completedAt);
        }
        Map<String, BingoLineCompletion> currentLines = lineCompletions.findByParticipantId(participant.getId())
                .stream().collect(Collectors.toMap(BingoLineCompletion::getLineKey, Function.identity()));
        List<BingoLineCompletion> obsolete = currentLines.entrySet().stream()
                .filter(entry -> !expectedLines.containsKey(entry.getKey())).map(Map.Entry::getValue).toList();
        if (!obsolete.isEmpty()) lineCompletions.deleteAllInBatch(obsolete);
        expectedLines.forEach((key, completedAt) -> {
            BingoLineCompletion current = currentLines.get(key);
            if (current == null) lineCompletions.save(new BingoLineCompletion(participant, key, completedAt));
            else current.repairCompletedAt(completedAt);
        });
        List<Instant> lineTimes = new ArrayList<>(expectedLines.values());
        lineTimes.sort(Comparator.naturalOrder());
        Instant targetAt = lineTimes.size() >= event.getTargetLines() ? lineTimes.get(event.getTargetLines() - 1) : null;
        Instant blackoutAt = event.isBlackoutEnabled() && completedAtByPosition.size() == event.getCells().size()
                ? completedAtByPosition.values().stream().max(Comparator.naturalOrder()).orElse(fallback) : null;
        participant.replaceDerivedProgress(completed.size(), targetAt, blackoutAt);
    }

    private void advance(Accumulator accumulator, BingoCell cell, PlayerMatchFacts facts,
                         boolean excludeBotCombatStats, boolean clanPlayRequired,
                         String evidenceMatchId, Instant evidenceAt) {
        BingoMissionEngine.Outcome calculated = missions.apply(
                cell, accumulator.outcome, facts, excludeBotCombatStats, clanPlayRequired);
        boolean firstCompletion = !accumulator.outcome.completed() && calculated.completed();
        boolean completed = accumulator.outcome.completed() || calculated.completed();
        accumulator.outcome = new BingoMissionEngine.Outcome(calculated.value(), calculated.occurrences(), completed);
        if (firstCompletion) {
            accumulator.completedAt = evidenceAt;
            accumulator.evidenceAt = evidenceAt;
            accumulator.evidenceMatchId = evidenceMatchId;
        }
    }

    private BingoEvent requireOnlyActive(Long communityId, Long communityGameId) {
        Instant now = clock.instant();
        List<BingoEvent> active = events.findByCommunityGameIdOrderByStartsAtDesc(communityGameId).stream()
                .filter(event -> event.getCommunity().getId().equals(communityId))
                .filter(event -> event.getStatus() == BingoStatus.ACTIVE)
                .filter(event -> !now.isBefore(event.getStartsAt()) && now.isBefore(event.getMatchStartUpperBoundExclusive()))
                .toList();
        if (active.size() != 1) conflict(active.isEmpty()
                ? "현재 진행 중인 빙고가 없습니다." : "진행 중인 빙고가 여러 개라 재집계를 거부했습니다.");
        return active.getFirst();
    }

    private List<String> competitionFingerprint(BingoEvent event, Long communityId, Long communityGameId) {
        return competitions.findByCommunityGameIdOrderByCreatedAtDesc(communityGameId).stream()
                .filter(competition -> competition.getStatus() == KillCompetitionStatus.COMPLETED)
                .filter(competition -> competition.getCommunity().getId().equals(communityId))
                .filter(competition -> !competition.getEndsAt().isBefore(event.getStartsAt())
                        && competition.getEndsAt().isBefore(event.getMatchStartUpperBoundExclusive()))
                .sorted(Comparator.comparing(KillCompetition::getEndsAt).thenComparing(KillCompetition::getId))
                .map(competition -> competition.getId() + ":" + competition.getEndsAt() + ":" + winnerMemberIds(competition))
                .toList();
    }

    private Set<Long> winnerMemberIds(KillCompetition competition) {
        return winnerResolver.winnerMembers(competition).stream().map(CommunityMember::getId)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private PlayerMatchFacts contentFact(KillCompetition competition) {
        return new PlayerMatchFacts("KILL_COMPETITION:" + competition.getId(), competition.getEndsAt(), null, null,
                Map.of(BingoMissionType.KILL_BET_WIN.name(), BigDecimal.ONE), List.of(), Map.of(), 0,
                competition.getEndsAt());
    }

    private Set<Integer> linePositions(String key, int size) {
        Set<Integer> positions = new LinkedHashSet<>();
        if (key.startsWith("ROW_")) {
            int row = Integer.parseInt(key.substring(4));
            for (int col = 0; col < size; col++) positions.add(row * size + col);
        } else if (key.startsWith("COL_")) {
            int col = Integer.parseInt(key.substring(4));
            for (int row = 0; row < size; row++) positions.add(row * size + col);
        } else if ("DIAG_MAIN".equals(key)) {
            for (int index = 0; index < size; index++) positions.add(index * size + index);
        } else if ("DIAG_ANTI".equals(key)) {
            for (int index = 0; index < size; index++) positions.add(index * size + size - index - 1);
        }
        return positions;
    }

    private TemporaryBingoRebuildResponse response(String status, String token, PreviewSnapshot snapshot) {
        List<TemporaryBingoRebuildResponse.ParticipantResult> rows = snapshot.participants().stream().map(participant ->
                new TemporaryBingoRebuildResponse.ParticipantResult(participant.participantId(),
                        participant.account().nickname(), participant.progress().stream()
                        .filter(ProgressSnapshot::changed).map(value ->
                        new TemporaryBingoRebuildResponse.ProgressChange(value.cellId(), value.missionType().name(),
                                value.title(), value.previousValue(), value.recomputedValue(),
                                value.previousCompleted(), value.recomputedCompleted())).toList())).toList();
        return new TemporaryBingoRebuildResponse(status, token, snapshot.eventId(), snapshot.eventTitle(),
                snapshot.calculatedAt(), snapshot.participants().size(), snapshot.matchCount(),
                snapshot.changedCount(), rows, List.of());
    }

    private TemporaryBingoRebuildResponse failed(BingoEvent event, Long userId, Long communityId,
                                               Long communityGameId, String operation, BuildResult result) {
        TemporaryBingoRebuildResponse response = new TemporaryBingoRebuildResponse("REBUILD_FAILED", null, event.getId(), event.getTitle(),
                clock.instant(), participants.findByEventIdOrderByIdAsc(event.getId()).size(), 0, 0,
                List.of(), List.copyOf(result.failures()));
        recordRebuildFailure(event.getId(), userId, communityId, communityGameId, operation, result);
        return response;
    }

    private void recordRebuildFailure(Long eventId, Long userId, Long communityId,
                                      Long communityGameId, String operation, BuildResult result) {
        List<String> stages = result.diagnostics().stream().map(FailureDiagnostic::stage).distinct().sorted().toList();
        boolean operationalFailure = result.diagnostics().stream().anyMatch(value -> value.exceptionClass() != null);
        if (!operationalFailure) log.warn("Bingo rebuild validation failed - bingoEventId={} communityId={} operation={} failureStages={} failureCount={}",
                eventId, communityId, operation, stages, result.failures().size());
        if (monitoring == null) return;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("jobName", "BINGO_REBUILD");
        metadata.put("bingoEventId", eventId);
        metadata.put("communityGameId", communityGameId);
        metadata.put("operation", operation);
        metadata.put("stage", stages.size() == 1 ? stages.getFirst() : "MULTIPLE_FAILURE_STAGES");
        metadata.put("failureStages", stages);
        metadata.put("failureCount", result.failures().size());
        metadata.put("matchIds", result.failures().stream().map(TemporaryBingoRebuildResponse.Failure::matchId)
                .filter(Objects::nonNull).distinct().limit(10).toList());
        metadata.put("exceptionClasses", result.diagnostics().stream().map(FailureDiagnostic::exceptionClass)
                .filter(Objects::nonNull).distinct().sorted().toList());
        metadata.put("upstreamStatuses", result.diagnostics().stream().map(FailureDiagnostic::httpStatus)
                .filter(Objects::nonNull).distinct().sorted().toList());
        try {
            if (operationalFailure) monitoring.recordError(MonitoringCategory.BINGO, MonitoringEventCode.BINGO_AGGREGATION_FAILED,
                    "Bingo rebuild failed", communityId, userId, "bingoEventId=" + eventId, metadata);
            else monitoring.recordWarn(MonitoringCategory.BINGO, MonitoringEventCode.BINGO_AGGREGATION_FAILED,
                    "Bingo rebuild validation failed", communityId, userId, "bingoEventId=" + eventId, metadata);
        } catch (RuntimeException observerFailure) {
            log.warn("Bingo rebuild monitoring unavailable - bingoEventId={} communityId={} operation={}",
                    eventId, communityId, operation, observerFailure);
        }
    }

    private FailureDiagnostic diagnostic(String stage, RuntimeException exception) {
        Integer status = null;
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof PubgApiException pubg && pubg.getUpstreamStatus() != null) {
                status = pubg.getUpstreamStatus();
                break;
            }
        }
        return new FailureDiagnostic(stage, exception == null ? null : exception.getClass().getSimpleName(), status);
    }

    private String telemetryFailureStage(Throwable exception) {
        boolean pubgFailure = false;
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.springframework.http.converter.HttpMessageConversionException
                    || cause instanceof org.springframework.web.client.UnknownContentTypeException) return "TELEMETRY_PARSE";
            if (cause instanceof PubgApiException) pubgFailure = true;
        }
        return pubgFailure ? "TELEMETRY_FETCH" : "TELEMETRY_PARSE";
    }

    private TemporaryBingoRebuildResponse.Failure failure(BingoParticipant participant, String matchId, String reason) {
        return new TemporaryBingoRebuildResponse.Failure(participant.getId(), participant.getPubgNickname(), matchId, reason);
    }
    private BingoParticipant participant(Long id, List<BingoParticipant> rows) {
        return rows.stream().filter(row -> row.getId().equals(id)).findFirst().orElseThrow();
    }
    private String title(BingoCell cell) {
        return blank(cell.getCustomTitle()) ? cell.getMissionType().name() : cell.getCustomTitle();
    }
    private String cellFingerprint(List<BingoCell> cells) {
        return cells.stream().sorted(Comparator.comparing(BingoCell::getId)).map(cell ->
                cell.getId() + "|" + cell.getMissionType() + "|" + cell.getAggregationType() + "|"
                        + cell.getOperator() + "|" + cell.getTargetValue() + "|" + cell.getOccurrenceTarget()
                        + "|" + new TreeMap<>(cell.getOptions())).collect(Collectors.joining(";"));
    }

    private String eventFingerprint(BingoEvent event) {
        return cellFingerprint(event.getCells()) + "|excludeBotCombatStats=" + event.isExcludeBotCombatStats()
                + "|clanPlayRequired=" + event.isClanPlayRequired();
    }
    private String reason(RuntimeException exception) {
        if (exception instanceof PubgApiException pubg && pubg.getReason() != null) return pubg.getReason();
        return "데이터 처리 중 오류가 발생했습니다. 관리자에게 문의해 주세요.";
    }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private void removeExpiredPreviews() {
        Instant now = clock.instant();
        previews.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }
    private void conflict(String message) { throw new ResponseStatusException(HttpStatus.CONFLICT, message); }

    private boolean sameRepairPlan(PreviewSnapshot before, PreviewSnapshot current) {
        if (!Objects.equals(before.eventId(), current.eventId())
                || !Objects.equals(before.lastAggregatedAt(), current.lastAggregatedAt())
                || !Objects.equals(before.cellFingerprint(), current.cellFingerprint())
                || !Objects.equals(before.competitionFingerprint(), current.competitionFingerprint())
                || before.matchCount() != current.matchCount()
                || before.participants().size() != current.participants().size()) return false;
        Map<Long, ParticipantSnapshot> currentParticipants = current.participants().stream()
                .collect(Collectors.toMap(ParticipantSnapshot::participantId, Function.identity()));
        for (ParticipantSnapshot previousParticipant : before.participants()) {
            ParticipantSnapshot currentParticipant = currentParticipants.get(previousParticipant.participantId());
            if (currentParticipant == null || !Objects.equals(previousParticipant.account(), currentParticipant.account())
                    || previousParticipant.progress().size() != currentParticipant.progress().size()) return false;
            Map<Long, ProgressSnapshot> currentProgress = currentParticipant.progress().stream()
                    .collect(Collectors.toMap(ProgressSnapshot::progressId, Function.identity()));
            for (ProgressSnapshot previous : previousParticipant.progress()) {
                ProgressSnapshot next = currentProgress.get(previous.progressId());
                if (next == null || !previous.sameCalculation(next)) return false;
            }
        }
        return true;
    }

    private static final class Accumulator {
        private BingoMissionEngine.Outcome outcome = BingoMissionEngine.Outcome.zero();
        private Instant completedAt;
        private String evidenceMatchId;
        private Instant evidenceAt;
    }
    private record AccountSnapshot(String accountId, String nickname, Long memberId, Instant eligibleFrom) {}
    private record ParticipantSnapshot(Long participantId, AccountSnapshot account,
                                       List<ProgressSnapshot> progress) {}
    private record ProgressSnapshot(Long progressId, Long cellId, BingoMissionType missionType, String title,
                                    BigDecimal previousValue, int previousOccurrences, boolean previousCompleted,
                                    Instant previousCompletedAt, BigDecimal recomputedValue, int recomputedOccurrences,
                                    boolean recomputedCompleted, Instant recomputedCompletedAt,
                                    String evidenceMatchId, Instant evidenceAt) {
        boolean changed() {
            return previousValue.compareTo(recomputedValue) != 0
                    || previousOccurrences != recomputedOccurrences
                    || previousCompleted != recomputedCompleted;
        }
        boolean matchesOriginal(BingoProgress row) {
            return previousValue.compareTo(row.getCurrentValue()) == 0
                    && previousOccurrences == row.getOccurrenceCount()
                    && previousCompleted == row.isCompleted()
                    && Objects.equals(previousCompletedAt, row.getCompletedAt());
        }
        boolean sameCalculation(ProgressSnapshot other) {
            return previousValue.compareTo(other.previousValue) == 0
                    && previousOccurrences == other.previousOccurrences
                    && previousCompleted == other.previousCompleted
                    && Objects.equals(previousCompletedAt, other.previousCompletedAt)
                    && recomputedValue.compareTo(other.recomputedValue) == 0
                    && recomputedOccurrences == other.recomputedOccurrences
                    && recomputedCompleted == other.recomputedCompleted
                    && Objects.equals(recomputedCompletedAt, other.recomputedCompletedAt)
                    && Objects.equals(evidenceMatchId, other.evidenceMatchId)
                    && Objects.equals(evidenceAt, other.evidenceAt);
        }
    }
    private record PreviewSnapshot(String token, Long eventId, Long communityId, Long communityGameId,
                                   String eventTitle, Instant calculatedAt, Instant expiresAt,
                                   Instant lastAggregatedAt, String cellFingerprint,
                                   List<String> competitionFingerprint,
                                   List<ParticipantSnapshot> participants,
                                   int matchCount, int changedCount) {}
    private record FailureDiagnostic(String stage, String exceptionClass, Integer httpStatus) {}
    private record BuildResult(PreviewSnapshot snapshot,
                               List<TemporaryBingoRebuildResponse.Failure> failures,
                               Set<FailureDiagnostic> diagnostics) {
        PreviewSnapshot snapshot(String token, Instant expiresAt) {
            return new PreviewSnapshot(token, snapshot.eventId(), snapshot.communityId(), snapshot.communityGameId(),
                    snapshot.eventTitle(), snapshot.calculatedAt(), expiresAt, snapshot.lastAggregatedAt(),
                    snapshot.cellFingerprint(), snapshot.competitionFingerprint(), snapshot.participants(),
                    snapshot.matchCount(), snapshot.changedCount());
        }
    }
}
