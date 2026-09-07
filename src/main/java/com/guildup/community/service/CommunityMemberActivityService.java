package com.guildup.community.service;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.activity.ClanActivityStatus;
import com.guildup.community.domain.CommunityGame;
import com.guildup.community.domain.CommunityGameActivityRule;
import com.guildup.community.domain.CommunityMember;
import com.guildup.community.domain.CommunityMemberAccount;
import com.guildup.community.domain.CommunityMemberActivityMatch;
import com.guildup.community.domain.CommunityMemberActivitySnapshot;
import com.guildup.community.domain.CommunityMemberStatus;
import com.guildup.community.dto.CommunityActivitySyncResponse;
import com.guildup.community.dto.CommunityMemberActivityDetailResponse;
import com.guildup.community.dto.CommunityMemberActivityListResponse;
import com.guildup.community.dto.MemberActivityMatchResponse;
import com.guildup.community.dto.MemberActivityRuleResponse;
import com.guildup.community.dto.MemberActivitySummaryResponse;
import com.guildup.community.repository.CommunityGameActivityRuleRepository;
import com.guildup.community.repository.CommunityGameActivitySyncRepository;
import com.guildup.community.repository.CommunityGameRepository;
import com.guildup.community.repository.CommunityMemberAccountRepository;
import com.guildup.community.repository.CommunityMemberActivitySnapshotRepository;
import com.guildup.community.repository.CommunityMemberRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 저장된 마지막 활동 스냅샷만 읽으며 PUBG API에는 접근하지 않는다. */
@Service
@Transactional(readOnly = true)
public class CommunityMemberActivityService {

    private final CommunityAccessService accessService;
    private final CommunityGameRepository gameRepository;
    private final CommunityGameActivityRuleRepository ruleRepository;
    private final CommunityGameActivitySyncRepository syncRepository;
    private final CommunityMemberRepository memberRepository;
    private final CommunityMemberAccountRepository accountRepository;
    private final CommunityMemberActivitySnapshotRepository snapshotRepository;
    private final CommunityActivitySyncPolicy syncPolicy;
    private final Clock clock;

    public CommunityMemberActivityService(
            CommunityAccessService accessService,
            CommunityGameRepository gameRepository,
            CommunityGameActivityRuleRepository ruleRepository,
            CommunityGameActivitySyncRepository syncRepository,
            CommunityMemberRepository memberRepository,
            CommunityMemberAccountRepository accountRepository,
            CommunityMemberActivitySnapshotRepository snapshotRepository,
            CommunityActivitySyncPolicy syncPolicy,
            Clock clock
    ) {
        this.accessService = accessService;
        this.gameRepository = gameRepository;
        this.ruleRepository = ruleRepository;
        this.syncRepository = syncRepository;
        this.memberRepository = memberRepository;
        this.accountRepository = accountRepository;
        this.snapshotRepository = snapshotRepository;
        this.syncPolicy = syncPolicy;
        this.clock = clock;
    }

    public CommunityMemberActivityListResponse getActivities(Long userId, Long communityId) {
        accessService.requireAccess(userId, communityId);
        CommunityGame game = requireSupportedGame(communityId);
        CommunityGameActivityRule rule = requireRule(game.getId());
        List<CommunityMember> members = memberRepository.findByCommunityIdAndStatusOrderByIdAsc(
                communityId, CommunityMemberStatus.ACTIVE
        );
        Map<Long, CommunityMemberActivitySnapshot> snapshots = snapshotRepository
                .findByCommunityGameIdOrderByCommunityMemberIdAsc(game.getId()).stream()
                .collect(Collectors.toMap(
                        snapshot -> snapshot.getCommunityMember().getId(),
                        Function.identity(),
                        (first, ignored) -> first,
                        LinkedHashMap::new
                ));
        Map<Long, CommunityMemberAccount> accounts = pubgAccountsByMemberId(communityId);
        List<MemberActivitySummaryResponse> summaries = members.stream()
                .map(member -> toSummary(member, snapshots.get(member.getId()), accounts.get(member.getId())))
                .toList();
        CommunityActivitySyncResponse sync = syncPolicy.describe(
                syncRepository.findByCommunityGameId(game.getId()).orElse(null),
                clock.instant()
        );
        return CommunityMemberActivityListResponse.of(
                MemberActivityRuleResponse.from(rule), sync, summaries
        );
    }

    public CommunityMemberActivityDetailResponse getActivity(
            Long userId,
            Long communityId,
            Long memberId
    ) {
        accessService.requireAccess(userId, communityId);
        CommunityGame game = requireSupportedGame(communityId);
        CommunityGameActivityRule rule = requireRule(game.getId());
        CommunityMember member = memberRepository.findById(memberId)
                .filter(candidate -> candidate.getCommunity().getId().equals(communityId))
                .filter(candidate -> candidate.getStatus() == CommunityMemberStatus.ACTIVE)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "클랜원을 찾을 수 없습니다."
                ));
        CommunityMemberActivitySnapshot snapshot = snapshotRepository
                .findByCommunityGameIdAndCommunityMemberId(game.getId(), memberId)
                .orElse(null);
        CommunityMemberAccount account = pubgAccountsByMemberId(communityId).get(memberId);
        MemberActivitySummaryResponse summary = toSummary(member, snapshot, account);
        List<MemberActivityMatchResponse> matches = snapshot == null
                ? List.of()
                : snapshot.getMatches().stream()
                .sorted(Comparator.comparing(CommunityMemberActivityMatch::getPlayedAt).reversed())
                .map(match -> MemberActivityMatchResponse.from(match, snapshot.getPubgAccountId()))
                .toList();
        return new CommunityMemberActivityDetailResponse(
                summary,
                summary.status(),
                summary.lastClanActivityAt(),
                MemberActivityRuleResponse.from(rule),
                matches
        );
    }

    private MemberActivitySummaryResponse toSummary(
            CommunityMember member,
            CommunityMemberActivitySnapshot snapshot,
            CommunityMemberAccount account
    ) {
        if (snapshot == null) {
            return new MemberActivitySummaryResponse(
                    member.getId(), member.getNickname(),
                    account == null ? null : account.getExternalUserId(),
                    account == null ? null : account.getExternalUsername(),
                    ClanActivityStatus.ACCOUNT_VERIFICATION_REQUIRED,
                    null, null, null
            );
        }
        return new MemberActivitySummaryResponse(
                member.getId(), member.getNickname(), snapshot.getPubgAccountId(),
                snapshot.getGameNickname(), snapshot.getActivityStatus(),
                snapshot.getLastPubgMatchAt(), snapshot.getLastClanActivityAt(),
                snapshot.getSynchronizedAt()
        );
    }

    private Map<Long, CommunityMemberAccount> pubgAccountsByMemberId(Long communityId) {
        return accountRepository.findByCommunityIdAndProvider(
                communityId, ExternalAccountProvider.PUBG
        ).stream().collect(Collectors.toMap(
                account -> account.getCommunityMember().getId(),
                Function.identity(),
                (first, ignored) -> first,
                LinkedHashMap::new
        ));
    }

    private CommunityGame requireSupportedGame(Long communityId) {
        return gameRepository.findByCommunityIdOrderByIdAsc(communityId).stream()
                .filter(game -> game.getGameType().supportsRosterActivityRule())
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "배틀그라운드 게임 설정을 찾을 수 없습니다."
                ));
    }

    private CommunityGameActivityRule requireRule(Long communityGameId) {
        return ruleRepository.findByCommunityGameId(communityGameId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "클랜 활동 규칙을 먼저 설정해 주세요."
                ));
    }
}
