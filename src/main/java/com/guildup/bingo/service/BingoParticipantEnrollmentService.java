package com.guildup.bingo.service;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.bingo.domain.*;
import com.guildup.bingo.repository.*;
import com.guildup.community.domain.*;
import com.guildup.community.repository.*;
import com.guildup.community.service.CurrentCommunityMemberService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
public class BingoParticipantEnrollmentService {
    private final CommunityUserRepository communityUsers;
    private final CurrentCommunityMemberService currentMembers;
    private final CommunityMemberAccountRepository accounts;
    private final BingoParticipantRepository participants;
    private final BingoProgressRepository progress;

    public BingoParticipantEnrollmentService(CommunityUserRepository communityUsers,
                                             CurrentCommunityMemberService currentMembers,
                                             CommunityMemberAccountRepository accounts,
                                             BingoParticipantRepository participants,
                                             BingoProgressRepository progress) {
        this.communityUsers = communityUsers; this.currentMembers = currentMembers; this.accounts = accounts;
        this.participants = participants; this.progress = progress;
    }

    public int enrollEligible(BingoEvent event, Instant now) {
        int added = 0;
        for (CommunityUser user : communityUsers.findByCommunityIdOrderByIdAsc(event.getCommunity().getId())) {
            Instant joinedAt = user.getJoinedAt();
            boolean initial = joinedAt == null || !joinedAt.isAfter(event.getStartsAt());
            if (!initial && !event.isAllowLateJoin()) continue;
            if (participants.existsByEventIdAndCommunityUserId(event.getId(), user.getId())) continue;
            Instant eligibleFrom = initial ? event.getStartsAt() : now;
            enroll(event, user, now, eligibleFrom); added++;
        }
        return added;
    }

    public BingoParticipant enrollLateUser(BingoEvent event, CommunityUser user, Instant now) {
        return participants.findByEventIdAndCommunityUserUserId(event.getId(), user.getUser().getId())
                .orElseGet(() -> enroll(event, user, now, now));
    }

    private BingoParticipant enroll(BingoEvent event, CommunityUser user, Instant joinedAt, Instant eligibleFrom) {
        Optional<CommunityMember> member = currentMembers.find(user.getUser().getId(), event.getCommunity().getId());
        CommunityMemberAccount account = member.flatMap(value -> accounts.findByCommunityMemberIdAndProvider(
                value.getId(), ExternalAccountProvider.PUBG)).orElse(null);
        BingoParticipant participant = participants.save(new BingoParticipant(
                event, user, member.orElse(null), account == null ? null : account.getExternalUserId(),
                account == null ? null : account.getExternalUsername(), joinedAt, eligibleFrom));
        progress.saveAll(event.getCells().stream().map(cell -> new BingoProgress(participant, cell, joinedAt)).toList());
        return participant;
    }
}
