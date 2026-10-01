package com.guildup.community.service;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.domain.CommunityMember;
import com.guildup.community.domain.CommunityMemberAccount;
import com.guildup.community.repository.CommunityRankingRepository;
import com.guildup.community.repository.CommunityRepository;
import com.guildup.community.dto.RankingSettingsResponse;
import com.guildup.community.domain.RankingPeriodType;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.guildup.community.dto.CommunityRankingEntryResponse;
import com.guildup.community.dto.CommunityRankingsResponse;
import com.guildup.community.dto.MyCommunityRankingResponse;
import com.guildup.community.repository.CommunityMemberAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class CommunityRankingService {
    private final CommunityAccessService access;
    private final CurrentCommunityMemberService currentMembers;
    private final CommunityRankingRepository rankingsRepository;
    private final CommunityRepository communities;
    private final Clock clock;
    private final CommunityMemberAccountRepository memberAccounts;

    public CommunityRankingService(CommunityAccessService access,
                                   CurrentCommunityMemberService currentMembers,
                                   CommunityRankingRepository rankingsRepository,
                                   CommunityRepository communities, Clock clock,
                                   CommunityMemberAccountRepository memberAccounts) {
        this.access = access;
        this.currentMembers = currentMembers;
        this.rankingsRepository = rankingsRepository;
        this.communities = communities;
        this.clock = clock;
        this.memberAccounts = memberAccounts;
    }

    public CommunityRankingsResponse getRankings(Long userId, Long communityId) {
        return getRankings(userId, communityId, null, null, null);
    }

    public CommunityRankingsResponse getRankings(Long userId, Long communityId, Integer year, Integer month, Integer quarter) {
        var membership = access.requireCommunityMember(userId, communityId);
        RankingPeriod period = RankingPeriod.resolve(membership.getCommunity().getRankingPeriodType(), year, month, quarter, clock);
        Long myMemberId = currentMembers.find(userId, communityId)
                .map(CommunityMember::getId).orElse(null);
        Map<Long, CommunityMemberAccount> discordAccounts = memberAccounts
                .findByCommunityIdAndProvider(communityId, ExternalAccountProvider.DISCORD).stream()
                .collect(Collectors.toMap(
                        account -> account.getCommunityMember().getId(), Function.identity()
                ));

        List<CommunityRankingEntryResponse> rankings = new ArrayList<>();
        Integer myRank = null;
        int myScore = 0;
        int rank = 0;
        for (var row : rankingsRepository.findRankings(communityId, period)) {
            rank++;
            int score = row.score();
            boolean me = Objects.equals(row.memberId(), myMemberId);
            if (me) {
                myRank = rank;
                myScore = score;
            }
            rankings.add(new CommunityRankingEntryResponse(
                    rank, row.memberId(), displayName(row.nickname(), discordAccounts.get(row.memberId())), score, me,
                    row.attendanceScore(), row.killCompetitionScore()
            ));
        }
        return new CommunityRankingsResponse(
                new MyCommunityRankingResponse(myRank, myScore), List.copyOf(rankings), period.response()
        );
    }

    public RankingSettingsResponse getSettings(Long userId, Long communityId) {
        return new RankingSettingsResponse(access.requireCommunityMember(userId, communityId).getCommunity().getRankingPeriodType());
    }

    @Transactional
    public RankingSettingsResponse updateSettings(Long userId, Long communityId, RankingPeriodType periodType) {
        access.requireCommunityAdmin(userId, communityId);
        if (periodType == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "집계 주기를 선택해 주세요.");
        var community = communities.findById(communityId).orElseThrow();
        community.changeRankingPeriodType(periodType);
        return new RankingSettingsResponse(community.getRankingPeriodType());
    }

    private String displayName(String nickname, CommunityMemberAccount account) {
        if (account != null && account.getExternalDisplayName() != null
                && !account.getExternalDisplayName().isBlank()) {
            return account.getExternalDisplayName();
        }
        return nickname;
    }
}
