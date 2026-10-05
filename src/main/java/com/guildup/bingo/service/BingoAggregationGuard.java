package com.guildup.bingo.service;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.bingo.domain.*;
import com.guildup.bingo.repository.BingoParticipantRepository;
import com.guildup.community.domain.CommunityMemberStatus;
import com.guildup.community.repository.CommunityMemberAccountRepository;
import com.guildup.pubg.support.PubgGameSupport;
import com.guildup.pubg.model.PubgPlatform;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** 기존 event version을 작업 세대로 사용한다. 전체/개인 준비와 반영은 같은 event lock 순서를 따른다. */
@Service
public class BingoAggregationGuard {
    private static final Logger log = LoggerFactory.getLogger(BingoAggregationGuard.class);
    private final EntityManager entityManager;
    private final BingoParticipantRepository participants;
    private final CommunityMemberAccountRepository accounts;

    public BingoAggregationGuard(EntityManager entityManager, BingoParticipantRepository participants,
                                 CommunityMemberAccountRepository accounts) {
        this.entityManager = entityManager; this.participants = participants; this.accounts = accounts;
    }

    public Snapshot claim(BingoEvent event) {
        // 먼저 준비 단계의 계정/상태 동기화를 저장한 뒤 version을 즉시 증가시킨다.
        entityManager.flush();
        // 비교 대상은 Java의 나노초 값이 아니라 DB에 실제 저장된 정밀도의 입력이다.
        entityManager.refresh(event);
        participants.findByEventIdOrderByIdAsc(event.getId()).forEach(entityManager::refresh);
        entityManager.lock(event, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        return snapshot(event);
    }

    public void verify(BingoEvent event, BingoAggregationPreparationService.PreparedAggregation work, Long participantId) {
        Snapshot prepared = work.snapshot();
        if (prepared == null || (event.getStatus() != BingoStatus.ACTIVE && event.getStatus() != BingoStatus.SETTLING)
                || !prepared.equals(snapshot(event)) || !consistentInputs(event, work, participantId)) {
            log.warn("Bingo stale aggregation rejected - eventId={} preparedVersion={} currentVersion={}",
                    event.getId(), prepared == null ? null : prepared.eventVersion(), event.getVersion());
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "집계 중 빙고 설정·참가 정보가 변경되었거나 새 집계가 시작되었습니다. 다시 집계해 주세요.");
        }
    }

    /** claim 직전 계정 교체/참가가 일어나도 실제 수집 입력과 버전 스냅샷이 서로 어긋나지 않아야 한다. */
    private boolean consistentInputs(BingoEvent event, BingoAggregationPreparationService.PreparedAggregation work,
                                     Long participantId) {
        Snapshot snapshot = work.snapshot();
        Map<Long, String> accountsByMember = new HashMap<>();
        snapshot.accounts().forEach(account -> accountsByMember.put(account.memberId(), account.accountId()));
        List<ParticipantInput> targets = snapshot.participants().stream()
                .filter(p -> participantId == null || participantId.equals(p.id())).toList();
        if (targets.stream().anyMatch(p -> p.memberId() != null
                && !Objects.equals(p.accountId(), accountsByMember.get(p.memberId())))) return false;
        List<BingoAggregationPreparationService.PreparedParticipant> expected = targets.stream()
                .filter(p -> p.accountId() != null).map(p -> new BingoAggregationPreparationService.PreparedParticipant(
                        p.id(), p.accountId(), p.eligibleFrom())).toList();
        Set<String> communityAccounts = new HashSet<>();
        snapshot.accounts().stream().filter(a -> a.memberStatus() == CommunityMemberStatus.ACTIVE)
                .map(AccountInput::accountId).filter(Objects::nonNull).forEach(communityAccounts::add);
        expected.stream().map(BingoAggregationPreparationService.PreparedParticipant::accountId).forEach(communityAccounts::add);
        return expected.equals(work.participants()) && communityAccounts.equals(work.communityAccounts())
                && event.getId().equals(work.eventId()) && snapshot.platform() == work.platform()
                && event.getStatus() == work.status() && event.getStartsAt().equals(work.startsAt())
                && event.getMatchStartUpperBoundExclusive().equals(work.endsExclusive())
                && event.isExcludeBotCombatStats() == work.excludeBotCombatStats();
    }

    private Snapshot snapshot(BingoEvent event) {
        List<ParticipantInput> participantInputs = participants.findByEventIdOrderByIdAsc(event.getId()).stream()
                .map(p -> new ParticipantInput(p.getId(), p.getVersion(), p.getPubgAccountId(), p.getEligibleFrom(),
                        p.getCommunityUser().getId(), p.getCommunityMember() == null ? null : p.getCommunityMember().getId(),
                        p.getCommunityMember() == null ? null : p.getCommunityMember().getStatus())).toList();
        List<AccountInput> accountInputs = accounts.findByCommunityIdAndProviderAndPlatform(event.getCommunity().getId(),
                        ExternalAccountProvider.PUBG, PubgGameSupport.requirePlatform(event.getCommunityGame())).stream()
                .map(a -> new AccountInput(a.getId(), a.getCommunityMember().getId(),
                        a.getCommunityMember().getStatus(), a.getExternalUserId()))
                .sorted(Comparator.comparing(AccountInput::id)).toList();
        List<CellInput> cells = event.getCells().stream().map(c -> new CellInput(c.getId(), c.getPosition(),
                c.getMissionType(), c.getAggregationType(), c.getOperator(), c.getTargetValue().stripTrailingZeros(),
                c.getOccurrenceTarget(), c.getOptions())).toList();
        return new Snapshot(event.getId(), event.getVersion(), event.getCommunityGame().getId(),
                PubgGameSupport.requirePlatform(event.getCommunityGame()), event.getStatus(),
                event.getStartsAt(), event.getEndsAt(), event.isExcludeBotCombatStats(), event.isClanPlayRequired(),
                event.getBoardSize(), event.getTargetLines(), event.isBlackoutEnabled(), event.isAllowLateJoin(),
                participantInputs, accountInputs, cells);
    }

    public record Snapshot(Long eventId, long eventVersion, Long communityGameId, PubgPlatform platform, BingoStatus status,
                           Instant startsAt, Instant endsAt, boolean excludeBots, boolean clanPlayRequired,
                           int boardSize, int targetLines, boolean blackoutEnabled, boolean allowLateJoin,
                           List<ParticipantInput> participants, List<AccountInput> accounts, List<CellInput> cells) {}
    public record ParticipantInput(Long id, long version, String accountId, Instant eligibleFrom,
                                   Long communityUserId, Long memberId, CommunityMemberStatus memberStatus) {}
    public record AccountInput(Long id, Long memberId, CommunityMemberStatus memberStatus, String accountId) {}
    public record CellInput(Long id, int position, BingoMissionType mission, BingoAggregationType aggregation,
                            BingoOperator operator, BigDecimal target, Integer occurrences, Map<String, Object> options) {}
}
