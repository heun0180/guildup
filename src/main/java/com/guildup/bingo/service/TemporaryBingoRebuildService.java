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
import com.guildup.pubg.model.*;
import com.guildup.pubg.service.*;
import com.guildup.pubg.support.PubgGameSupport;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
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

    public TemporaryBingoRebuildResponse preview(Long userId, Long communityId, Long communityGameId) {
        CommunityGame game = gameAccess.requireManageable(userId, communityId, communityGameId, GameCapability.BINGO);
        BingoEvent event = requireOnlyActive(communityId, communityGameId);
        ReentrantLock lock = eventLocks.computeIfAbsent(event.getId(), ignored -> new ReentrantLock());
        if (!lock.tryLock()) conflict("REBUILD_IN_PROGRESS");
        try {
            removeExpiredPreviews();
            BuildResult result = calculate(event, communityId, communityGameId,
                    PubgGameSupport.requireShard(game.getGameType()));
            if (!result.failures().isEmpty()) return failed(event, result.failures());
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
        gameAccess.requireManageable(userId, communityId, communityGameId, GameCapability.BINGO);
        PreviewSnapshot snapshot = previews.get(previewToken);
        if (snapshot == null || !snapshot.expiresAt().isAfter(clock.instant()))
            conflict("재집계 미리보기가 없거나 만료되었습니다. 다시 미리보기를 실행해 주세요.");
        if (!snapshot.communityId().equals(communityId) || !snapshot.communityGameId().equals(communityGameId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "다른 커뮤니티의 미리보기는 적용할 수 없습니다.");
        ReentrantLock lock = eventLocks.computeIfAbsent(snapshot.eventId(), ignored -> new ReentrantLock());
        if (!lock.tryLock()) conflict("REBUILD_IN_PROGRESS");
        try {
            TemporaryBingoRebuildResponse applied = transactions.execute(status -> applySnapshot(snapshot));
            previews.remove(previewToken);
            return applied;
        } finally {
            lock.unlock();
        }
    }

    private BuildResult calculate(BingoEvent event, Long communityId, Long communityGameId, String shard) {
        Instant calculatedAt = clock.instant();
        List<BingoParticipant> participantRows = participants.findByEventIdOrderByIdAsc(event.getId());
        List<BingoCell> cells = event.getCells();
        if (cells.size() != 16)
            conflict("현재 활성 빙고가 16개 셀로 구성되어 있지 않습니다.");

        List<CommunityMemberAccount> currentAccounts = memberAccounts.findByCommunityIdAndProvider(
                communityId, ExternalAccountProvider.PUBG);
        Map<Long, CommunityMemberAccount> accountByMember = currentAccounts.stream().collect(Collectors.toMap(
                account -> account.getCommunityMember().getId(), Function.identity(), (left, right) -> left));
        List<TemporaryBingoRebuildResponse.Failure> failures = new ArrayList<>();
        Map<Long, AccountSnapshot> accounts = new LinkedHashMap<>();
        for (BingoParticipant participant : participantRows) {
            CommunityMember member = participant.getCommunityMember();
            CommunityMemberAccount account = member == null ? null : accountByMember.get(member.getId());
            if (account == null || blank(account.getExternalUserId())) {
                failures.add(failure(participant, null, "현재 연결된 PUBG accountId가 없습니다."));
            } else {
                accounts.put(participant.getId(), new AccountSnapshot(account.getExternalUserId(),
                        account.getExternalUsername(), member.getId(), participant.getEligibleFrom()));
            }
        }
        if (!failures.isEmpty()) return new BuildResult(null, failures);

        List<PubgPlayer> loadedPlayers;
        try {
            loadedPlayers = players.findByAccountIdsFresh(shard,
                    accounts.values().stream().map(AccountSnapshot::accountId).toList());
        } catch (RuntimeException exception) {
            participantRows.forEach(participant -> failures.add(failure(participant, null,
                    "Player API 조회 실패: " + reason(exception))));
            return new BuildResult(null, failures);
        }
        Map<String, PubgPlayer> playerByAccount = loadedPlayers.stream().collect(Collectors.toMap(
                PubgPlayer::accountId, Function.identity(), (left, right) -> left));
        Map<Long, Set<String>> candidates = new LinkedHashMap<>();
        for (BingoParticipant participant : participantRows) {
            AccountSnapshot account = accounts.get(participant.getId());
            PubgPlayer player = playerByAccount.get(account.accountId());
            if (player == null) {
                failures.add(failure(participant, null, "Player API 응답에 현재 accountId가 없습니다."));
                continue;
            }
            Set<String> union = new LinkedHashSet<>(processedMatches.findMatchIds(event.getId(), participant.getId()));
            union.addAll(player.matchIds());
            candidates.put(participant.getId(), union);
        }
        if (!failures.isEmpty()) return new BuildResult(null, failures);

        Map<String, Set<Long>> participantIdsByMatch = new LinkedHashMap<>();
        candidates.forEach((participantId, ids) -> ids.forEach(matchId ->
                participantIdsByMatch.computeIfAbsent(matchId, ignored -> new LinkedHashSet<>()).add(participantId)));
        Map<String, PubgMatch> loadedMatches = new LinkedHashMap<>();
        for (Map.Entry<String, Set<Long>> entry : participantIdsByMatch.entrySet()) {
            try {
                PubgMatch match = matches.findUniqueMatchesFresh(shard, List.of(entry.getKey())).get(entry.getKey());
                if (match == null) entry.getValue().forEach(id -> failures.add(failure(participant(id, participantRows),
                        entry.getKey(), "Match 원본을 다시 조회할 수 없습니다.")));
                else loadedMatches.put(entry.getKey(), match);
            } catch (RuntimeException exception) {
                entry.getValue().forEach(id -> failures.add(failure(participant(id, participantRows),
                        entry.getKey(), "Match API 조회 실패: " + reason(exception))));
            }
        }
        if (!failures.isEmpty()) return new BuildResult(null, failures);

        Set<String> communityAccounts = currentAccounts.stream()
                .filter(account -> account.getCommunityMember().getStatus() == CommunityMemberStatus.ACTIVE)
                .map(CommunityMemberAccount::getExternalUserId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        communityAccounts.addAll(accounts.values().stream().map(AccountSnapshot::accountId).toList());
        boolean requireTelemetry = cells.stream().map(BingoCell::getMissionType).anyMatch(TELEMETRY_REQUIRED::contains);

        Map<Long, Map<Long, Accumulator>> accumulators = new LinkedHashMap<>();
        participantRows.forEach(participant -> {
            Map<Long, Accumulator> byCell = new LinkedHashMap<>();
            cells.forEach(cell -> byCell.put(cell.getId(), new Accumulator()));
            accumulators.put(participant.getId(), byCell);
        });
        List<ProcessedAddition> processedAdditions = new ArrayList<>();
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
            Map<String, PlayerMatchFacts> matchFacts;
            try {
                matchFacts = requireTelemetry ? facts.factsRequired(match, communityAccounts)
                        : facts.facts(match, communityAccounts);
            } catch (RuntimeException exception) {
                eligibleParticipants.forEach(id -> failures.add(failure(participant(id, participantRows), match.matchId(),
                        "Telemetry 조회 실패: " + reason(exception))));
                continue;
            }
            for (Long participantId : eligibleParticipants) {
                AccountSnapshot account = accounts.get(participantId);
                PlayerMatchFacts playerFacts = matchFacts.get(account.accountId());
                if (playerFacts == null) {
                    failures.add(failure(participant(participantId, participantRows), match.matchId(),
                            "Match 참가자 원본에 현재 accountId가 없습니다."));
                    continue;
                }
                for (BingoCell cell : cells) {
                    if (cell.getMissionType().source() != BingoMissionSource.PUBG_MATCH) continue;
                    advance(accumulators.get(participantId).get(cell.getId()), cell, playerFacts,
                            match.matchId(), playerFacts.latestEvidenceAt() == null ? match.playedAt() : playerFacts.latestEvidenceAt());
                }
                processedAdditions.add(new ProcessedAddition(participantId, match.matchId(), match.playedAt()));
                countedMatches.add(match.matchId());
            }
        }
        if (!failures.isEmpty()) return new BuildResult(null, failures);

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
                    advance(accumulators.get(participant.getId()).get(cell.getId()), cell, contentFact,
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
                event.getLastAggregatedAt(), cellFingerprint(cells), List.copyOf(competitionFingerprint),
                snapshots, processedAdditions, countedMatches.size(), changed);
        return new BuildResult(snapshot, List.of());
    }

    private TemporaryBingoRebuildResponse applySnapshot(PreviewSnapshot snapshot) {
        BingoEvent event = events.findForUpdate(snapshot.eventId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.CONFLICT, "활성 빙고가 사라졌습니다."));
        BingoEvent active = requireOnlyActive(snapshot.communityId(), snapshot.communityGameId());
        if (!active.getId().equals(event.getId())) conflict("활성 빙고가 미리보기 이후 변경되었습니다.");
        if (!Objects.equals(snapshot.lastAggregatedAt(), event.getLastAggregatedAt())
                || !Objects.equals(snapshot.cellFingerprint(), cellFingerprint(event.getCells())))
            conflict("미리보기 이후 빙고 또는 일반 집계 데이터가 변경되었습니다. 다시 미리보기를 실행해 주세요.");

        Map<Long, CommunityMemberAccount> currentAccounts = memberAccounts.findByCommunityIdAndProvider(
                        snapshot.communityId(), ExternalAccountProvider.PUBG).stream()
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
            participant.synchronizePubgAccount(participantSnapshot.account().accountId(), participantSnapshot.account().nickname());
            Map<Long, BingoProgress> rows = progress.findByParticipantIdOrderByCellPositionAsc(participant.getId())
                    .stream().collect(Collectors.toMap(BingoProgress::getId, Function.identity()));
            participantSnapshot.progress().forEach(value -> rows.get(value.progressId()).replaceSnapshot(
                    value.recomputedValue(), value.recomputedOccurrences(), value.recomputedCompleted(),
                    value.recomputedCompletedAt(), value.evidenceMatchId(), value.evidenceAt(), now));
            rebuildLines(event, participant, participantSnapshot.progress(), now);
        }
        Map<Long, BingoParticipant> participantMap = currentParticipants;
        for (ProcessedAddition addition : snapshot.processedAdditions()) {
            if (!processedMatches.existsByEventIdAndParticipantIdAndMatchId(
                    event.getId(), addition.participantId(), addition.matchId())) {
                processedMatches.save(new BingoProcessedMatch(event, participantMap.get(addition.participantId()),
                        addition.matchId(), addition.matchStartedAt(), now));
            }
        }
        return response("REBUILD_APPLIED", snapshot.token(), snapshot);
    }

    private void rebuildLines(BingoEvent event, BingoParticipant participant,
                              List<ProgressSnapshot> values, Instant fallback) {
        List<BingoLineCompletion> oldLines = lineCompletions.findByParticipantId(participant.getId());
        if (!oldLines.isEmpty()) lineCompletions.deleteAllInBatch(oldLines);
        Map<Integer, Instant> completedAtByPosition = new HashMap<>();
        Map<Long, Integer> positions = event.getCells().stream().collect(Collectors.toMap(BingoCell::getId, BingoCell::getPosition));
        values.stream().filter(ProgressSnapshot::recomputedCompleted).forEach(value -> completedAtByPosition.put(
                positions.get(value.cellId()), Optional.ofNullable(value.recomputedCompletedAt()).orElse(fallback)));
        Set<String> completed = lineCalculator.completedLines(event.getBoardSize(), completedAtByPosition.keySet());
        List<Instant> lineTimes = new ArrayList<>();
        for (String key : completed) {
            Instant completedAt = linePositions(key, event.getBoardSize()).stream()
                    .map(completedAtByPosition::get).max(Comparator.naturalOrder()).orElse(fallback);
            lineCompletions.save(new BingoLineCompletion(participant, key, completedAt));
            lineTimes.add(completedAt);
        }
        lineTimes.sort(Comparator.naturalOrder());
        Instant targetAt = lineTimes.size() >= event.getTargetLines() ? lineTimes.get(event.getTargetLines() - 1) : null;
        Instant blackoutAt = event.isBlackoutEnabled() && completedAtByPosition.size() == event.getCells().size()
                ? completedAtByPosition.values().stream().max(Comparator.naturalOrder()).orElse(fallback) : null;
        participant.replaceDerivedProgress(completed.size(), targetAt, blackoutAt);
    }

    private void advance(Accumulator accumulator, BingoCell cell, PlayerMatchFacts facts,
                         String evidenceMatchId, Instant evidenceAt) {
        BingoMissionEngine.Outcome calculated = missions.apply(cell, accumulator.outcome, facts);
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
                        participant.account().nickname(), participant.progress().stream().map(value ->
                        new TemporaryBingoRebuildResponse.ProgressChange(value.cellId(), value.missionType().name(),
                                value.title(), value.previousValue(), value.recomputedValue(),
                                value.previousCompleted(), value.recomputedCompleted())).toList())).toList();
        return new TemporaryBingoRebuildResponse(status, token, snapshot.eventId(), snapshot.eventTitle(),
                snapshot.calculatedAt(), snapshot.participants().size(), snapshot.matchCount(),
                snapshot.changedCount(), rows, List.of());
    }

    private TemporaryBingoRebuildResponse failed(BingoEvent event,
                                                  List<TemporaryBingoRebuildResponse.Failure> failures) {
        return new TemporaryBingoRebuildResponse("REBUILD_FAILED", null, event.getId(), event.getTitle(),
                clock.instant(), participants.findByEventIdOrderByIdAsc(event.getId()).size(), 0, 0,
                List.of(), List.copyOf(failures));
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
    private String reason(RuntimeException exception) {
        String message = exception.getMessage();
        return blank(message) ? exception.getClass().getSimpleName() : message;
    }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private void removeExpiredPreviews() {
        Instant now = clock.instant();
        previews.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }
    private void conflict(String message) { throw new ResponseStatusException(HttpStatus.CONFLICT, message); }

    private static final class Accumulator {
        private BingoMissionEngine.Outcome outcome = BingoMissionEngine.Outcome.zero();
        private Instant completedAt;
        private String evidenceMatchId;
        private Instant evidenceAt;
    }
    private record AccountSnapshot(String accountId, String nickname, Long memberId, Instant eligibleFrom) {}
    private record ProcessedAddition(Long participantId, String matchId, Instant matchStartedAt) {}
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
    }
    private record PreviewSnapshot(String token, Long eventId, Long communityId, Long communityGameId,
                                   String eventTitle, Instant calculatedAt, Instant expiresAt,
                                   Instant lastAggregatedAt, String cellFingerprint,
                                   List<String> competitionFingerprint,
                                   List<ParticipantSnapshot> participants,
                                   List<ProcessedAddition> processedAdditions,
                                   int matchCount, int changedCount) {}
    private record BuildResult(PreviewSnapshot snapshot,
                               List<TemporaryBingoRebuildResponse.Failure> failures) {
        PreviewSnapshot snapshot(String token, Instant expiresAt) {
            return new PreviewSnapshot(token, snapshot.eventId(), snapshot.communityId(), snapshot.communityGameId(),
                    snapshot.eventTitle(), snapshot.calculatedAt(), expiresAt, snapshot.lastAggregatedAt(),
                    snapshot.cellFingerprint(), snapshot.competitionFingerprint(), snapshot.participants(),
                    snapshot.processedAdditions(), snapshot.matchCount(), snapshot.changedCount());
        }
    }
}
