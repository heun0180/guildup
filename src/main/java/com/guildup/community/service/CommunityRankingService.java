package com.guildup.community.service;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.domain.CommunityMember;
import com.guildup.community.domain.CommunityMemberAccount;
import com.guildup.community.domain.CommunityMemberStatus;
import com.guildup.community.dto.CommunityRankingEntryResponse;
import com.guildup.community.dto.CommunityRankingsResponse;
import com.guildup.community.dto.MyCommunityRankingResponse;
import com.guildup.community.repository.CommunityMemberAccountRepository;
import com.guildup.community.repository.CommunityMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final CommunityMemberRepository members;
    private final CommunityMemberAccountRepository memberAccounts;

    public CommunityRankingService(CommunityAccessService access,
                                   CurrentCommunityMemberService currentMembers,
                                   CommunityMemberRepository members,
                                   CommunityMemberAccountRepository memberAccounts) {
        this.access = access;
        this.currentMembers = currentMembers;
        this.members = members;
        this.memberAccounts = memberAccounts;
    }

    public CommunityRankingsResponse getRankings(Long userId, Long communityId) {
        access.requireCommunityMember(userId, communityId);
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
        for (Object[] row : members.findRankingRows(communityId, CommunityMemberStatus.ACTIVE)) {
            rank++;
            CommunityMember member = (CommunityMember) row[0];
            int score = ((Number) row[1]).intValue();
            boolean me = Objects.equals(member.getId(), myMemberId);
            if (me) {
                myRank = rank;
                myScore = score;
            }
            rankings.add(new CommunityRankingEntryResponse(
                    rank, member.getId(), displayName(member, discordAccounts.get(member.getId())), score, me
            ));
        }
        return new CommunityRankingsResponse(
                new MyCommunityRankingResponse(myRank, myScore), List.copyOf(rankings)
        );
    }

    private String displayName(CommunityMember member, CommunityMemberAccount account) {
        if (account != null && account.getExternalDisplayName() != null
                && !account.getExternalDisplayName().isBlank()) {
            return account.getExternalDisplayName();
        }
        return member.getNickname();
    }
}
