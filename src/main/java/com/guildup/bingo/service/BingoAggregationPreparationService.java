package com.guildup.bingo.service;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.bingo.domain.*;
import com.guildup.bingo.repository.BingoEventRepository;
import com.guildup.bingo.repository.BingoParticipantRepository;
import com.guildup.community.domain.CommunityMemberAccount;
import com.guildup.community.domain.CommunityMemberStatus;
import com.guildup.community.repository.CommunityMemberAccountRepository;
import com.guildup.community.service.CommunityAccessService;
import com.guildup.pubg.support.PubgGameSupport;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 외부 API 전에 필요한 검증/동기화만 짧게 끝낸다. */
@Service
public class BingoAggregationPreparationService {
    private final BingoEventRepository events;
    private final BingoParticipantRepository participants;
    private final CommunityMemberAccountRepository memberAccounts;
    private final CommunityAccessService access;
    private final BingoParticipantEnrollmentService enrollment;
    private final Clock clock;

    public BingoAggregationPreparationService(BingoEventRepository events, BingoParticipantRepository participants,
            CommunityMemberAccountRepository memberAccounts, CommunityAccessService access,
            BingoParticipantEnrollmentService enrollment, Clock clock) {
        this.events = events; this.participants = participants; this.memberAccounts = memberAccounts;
        this.access = access; this.enrollment = enrollment; this.clock = clock;
    }

    @Transactional
    public PreparedAggregation prepare(Long userId, Long communityId, Long bingoId) {
        return prepareAll(userId, communityId, bingoId);
    }

    @Transactional
    public PreparedAggregation prepareAll(Long userId, Long communityId, Long bingoId) {
        access.requireCommunityAdmin(userId, communityId);
        Instant now = clock.instant();
        BingoEvent event = events.findForUpdate(bingoId)
                .filter(value -> value.getCommunity().getId().equals(communityId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "빙고를 찾을 수 없습니다."));
        List<BingoEvent> communityEvents = events.findByCommunityGameIdOrderByStartsAtDesc(event.getCommunityGame().getId());
        communityEvents.stream().filter(value -> value.getStatus() == BingoStatus.ACTIVE).forEach(value -> value.refreshStatus(now));
        boolean anotherActive = communityEvents.stream().anyMatch(value -> !value.getId().equals(event.getId())
                && value.getStatus() == BingoStatus.ACTIVE);
        if (!anotherActive) event.refreshStatus(now);
        if (event.getStatus() != BingoStatus.ACTIVE && event.getStatus() != BingoStatus.SETTLING)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "진행 중이거나 정산 중인 빙고만 집계할 수 있습니다.");
        if (event.getLastAggregatedAt() != null && now.isBefore(event.getLastAggregatedAt().plus(BingoEvent.AGGREGATION_COOLDOWN)))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "빙고 집계는 30분에 한 번만 실행할 수 있습니다.");

        enrollment.enrollEligible(event, now);
        List<BingoParticipant> participantRows = participants.findByEventIdOrderByIdAsc(event.getId());
        List<CommunityMemberAccount> accounts = memberAccounts.findByCommunityIdAndProvider(communityId, ExternalAccountProvider.PUBG);
        Map<Long, CommunityMemberAccount> byMember = accounts.stream().collect(Collectors.toMap(
                value -> value.getCommunityMember().getId(), Function.identity(), (left, right) -> left));
        participantRows.stream().filter(value -> value.getCommunityMember() != null).forEach(participant -> {
            CommunityMemberAccount account = byMember.get(participant.getCommunityMember().getId());
            participant.synchronizePubgAccount(account == null ? null : account.getExternalUserId(),
                    account == null ? null : account.getExternalUsername());
        });
        List<PreparedParticipant> connected = participantRows.stream().filter(value -> value.getPubgAccountId() != null)
                .map(value -> new PreparedParticipant(value.getId(), value.getPubgAccountId(), value.getEligibleFrom())).toList();
        Set<String> communityAccounts = accounts.stream()
                .filter(value -> value.getCommunityMember().getStatus() == CommunityMemberStatus.ACTIVE)
                .map(CommunityMemberAccount::getExternalUserId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        connected.forEach(value -> communityAccounts.add(value.accountId()));
        return new PreparedAggregation(event.getId(), PubgGameSupport.requireShard(event.getCommunityGame().getGameType()),
                event.getStartsAt(), event.getMatchStartUpperBoundExclusive(), event.getStatus(),
                List.copyOf(connected), Set.copyOf(communityAccounts), participantRows.size());
    }

    /** 요청값으로 participantId를 받지 않고 로그인 사용자의 참가자 행만 준비한다. */
    @Transactional
    public PreparedAggregation preparePersonal(Long userId, Long communityId, Long bingoId) {
        access.requireCommunityMember(userId, communityId);
        Instant now = clock.instant();
        BingoEvent event = events.findById(bingoId)
                .filter(value -> value.getCommunity().getId().equals(communityId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "빙고를 찾을 수 없습니다."));
        BingoStatus effectiveStatus = effectiveStatus(event, now);
        requireAggregatable(effectiveStatus);

        BingoParticipant participant = participants.findByEventIdAndCommunityUserUserId(bingoId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "현재 빙고 참가자가 아닙니다."));
        if (participant.getLastAggregatedAt() != null
                && now.isBefore(participant.getLastAggregatedAt().plus(BingoEvent.AGGREGATION_COOLDOWN)))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "내 기록 집계는 30분에 한 번만 실행할 수 있습니다.");
        if (participant.getCommunityMember() == null
                || participant.getCommunityMember().getStatus() != CommunityMemberStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "로그인 사용자와 연결된 활성 클랜원이 없습니다.");

        CommunityMemberAccount account = memberAccounts.findByCommunityMemberIdAndProvider(
                participant.getCommunityMember().getId(), ExternalAccountProvider.PUBG).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.CONFLICT, "PUBG 계정을 연결한 뒤 다시 시도해 주세요."));
        if (account.getExternalUserId() == null || account.getExternalUserId().isBlank())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PUBG 계정을 연결한 뒤 다시 시도해 주세요.");
        participant.synchronizePubgAccount(account.getExternalUserId(), account.getExternalUsername());

        Set<String> communityAccounts = activeCommunityAccounts(communityId);
        communityAccounts.add(account.getExternalUserId());
        PreparedParticipant preparedParticipant = new PreparedParticipant(
                participant.getId(), account.getExternalUserId(), participant.getEligibleFrom());
        return new PreparedAggregation(event.getId(), PubgGameSupport.requireShard(event.getCommunityGame().getGameType()),
                event.getStartsAt(), event.getMatchStartUpperBoundExclusive(), effectiveStatus,
                List.of(preparedParticipant), Set.copyOf(communityAccounts), 1);
    }

    /** 개인 Job key를 만들기 위한 가벼운 조회다. 쓰기 잠금을 사용하지 않는다. */
    @Transactional(readOnly = true)
    public Long resolvePersonalParticipantId(Long userId, Long communityId, Long bingoId) {
        access.requireCommunityMember(userId, communityId);
        BingoEvent event = events.findById(bingoId)
                .filter(value -> value.getCommunity().getId().equals(communityId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "빙고를 찾을 수 없습니다."));
        return participants.findByEventIdAndCommunityUserUserId(bingoId, userId)
                .map(BingoParticipant::getId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "현재 빙고 참가자가 아닙니다."));
    }

    /** POST 요청에서 참가/계정/상태 오류를 worker 생성 전에 명확히 반환한다. */
    @Transactional(readOnly = true)
    public Long validatePersonalRequest(Long userId, Long communityId, Long bingoId) {
        access.requireCommunityMember(userId, communityId);
        Instant now = clock.instant();
        BingoEvent event = events.findById(bingoId)
                .filter(value -> value.getCommunity().getId().equals(communityId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "빙고를 찾을 수 없습니다."));
        requireAggregatable(effectiveStatus(event, now));
        BingoParticipant participant = participants.findByEventIdAndCommunityUserUserId(bingoId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "현재 빙고 참가자가 아닙니다."));
        if (participant.getLastAggregatedAt() != null
                && now.isBefore(participant.getLastAggregatedAt().plus(BingoEvent.AGGREGATION_COOLDOWN)))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "내 기록 집계는 30분에 한 번만 실행할 수 있습니다.");
        if (participant.getCommunityMember() == null
                || participant.getCommunityMember().getStatus() != CommunityMemberStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "로그인 사용자와 연결된 활성 클랜원이 없습니다.");
        CommunityMemberAccount account = memberAccounts.findByCommunityMemberIdAndProvider(
                participant.getCommunityMember().getId(), ExternalAccountProvider.PUBG).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.CONFLICT, "PUBG 계정을 연결한 뒤 다시 시도해 주세요."));
        if (account.getExternalUserId() == null || account.getExternalUserId().isBlank())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PUBG 계정을 연결한 뒤 다시 시도해 주세요.");
        return participant.getId();
    }

    private Set<String> activeCommunityAccounts(Long communityId) {
        return memberAccounts.findByCommunityIdAndProvider(communityId, ExternalAccountProvider.PUBG).stream()
                .filter(value -> value.getCommunityMember().getStatus() == CommunityMemberStatus.ACTIVE)
                .map(CommunityMemberAccount::getExternalUserId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private BingoStatus effectiveStatus(BingoEvent event, Instant now) {
        BingoStatus status = event.getStatus();
        if (status == BingoStatus.SCHEDULED && !now.isBefore(event.getStartsAt())) status = BingoStatus.ACTIVE;
        if (status == BingoStatus.ACTIVE && !now.isBefore(event.getMatchStartUpperBoundExclusive())) status = BingoStatus.SETTLING;
        return status;
    }

    private void requireAggregatable(BingoStatus status) {
        if (status != BingoStatus.ACTIVE && status != BingoStatus.SETTLING)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "진행 중이거나 정산 중인 빙고만 집계할 수 있습니다.");
    }

    public record PreparedAggregation(Long eventId, String shard, Instant startsAt, Instant endsExclusive,
                                      BingoStatus status, List<PreparedParticipant> participants,
                                      Set<String> communityAccounts, int participantCount) {}
    public record PreparedParticipant(Long participantId, String accountId, Instant eligibleFrom) {}
}
