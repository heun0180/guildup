package com.guildup.killcompetition.service;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.domain.CommunityMember;
import com.guildup.community.domain.CommunityMemberAccount;
import com.guildup.community.repository.CommunityMemberAccountRepository;
import com.guildup.community.service.CommunityMemberPubgIdentityService;
import com.guildup.community.service.CurrentCommunityMemberService;
import com.guildup.killcompetition.domain.KillCompetition;
import com.guildup.killcompetition.domain.KillCompetitionParticipant;
import com.guildup.killcompetition.domain.KillCompetitionStatus;
import com.guildup.killcompetition.repository.KillCompetitionParticipantRepository;
import com.guildup.killcompetition.repository.KillCompetitionRepository;
import com.guildup.pubg.model.PubgPlayer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;

/** PUBG 외부 호출 전후의 참가 조건 확인과 DB 반영을 짧은 트랜잭션으로 나눈다. */
@Service
public class KillCompetitionParticipationStore {
    private final KillCompetitionRepository competitions;
    private final KillCompetitionParticipantRepository participants;
    private final CurrentCommunityMemberService currentMembers;
    private final CommunityMemberAccountRepository accounts;
    private final CommunityMemberPubgIdentityService identities;

    public KillCompetitionParticipationStore(KillCompetitionRepository competitions,
                                             KillCompetitionParticipantRepository participants,
                                             CurrentCommunityMemberService currentMembers,
                                             CommunityMemberAccountRepository accounts,
                                             CommunityMemberPubgIdentityService identities) {
        this.competitions = competitions;
        this.participants = participants;
        this.currentMembers = currentMembers;
        this.accounts = accounts;
        this.identities = identities;
    }

    @Transactional(readOnly = true)
    public JoinPreparation prepare(Long userId, Long communityId, Long competitionId) {
        KillCompetition competition = requireDetail(communityId, competitionId);
        if (competition.getStatus() != KillCompetitionStatus.RECRUITING) conflict("참가 모집이 종료되었습니다.");
        CommunityMember member = currentMembers.require(userId, communityId);
        if (participants.existsByCompetitionIdAndCommunityMemberId(competitionId, member.getId())) {
            conflict("이미 참가한 킬내기입니다.");
        }
        var identity = identities.find(member).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                "Discord 닉네임에서 PUBG 인게임 닉네임을 확인할 수 없습니다. 커뮤니티 닉네임 형식을 확인해 주세요."));
        return new JoinPreparation(member.getId(), identity.shard(), identity.nickname(), identity.accountId());
    }

    @Transactional
    public void commit(Long userId, Long communityId, Long competitionId,
                       JoinPreparation preparation, PubgPlayer resolvedPlayer) {
        KillCompetition competition = requireForUpdate(communityId, competitionId);
        if (competition.getStatus() != KillCompetitionStatus.RECRUITING) conflict("참가 모집이 종료되었습니다.");
        CommunityMember member = currentMembers.requireForUpdate(userId, communityId);
        if (!Objects.equals(member.getId(), preparation.memberId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "현재 클랜원 정보가 변경되었습니다.");
        }
        if (participants.existsByCompetitionIdAndCommunityMemberId(competitionId, member.getId())) {
            conflict("이미 참가한 킬내기입니다.");
        }

        CommunityMemberAccount account = accounts.findByCommunityMemberIdAndProvider(
                member.getId(), ExternalAccountProvider.PUBG).filter(this::usable).orElse(null);
        if (account == null) {
            if (resolvedPlayer == null || resolvedPlayer.accountId() == null || resolvedPlayer.accountId().isBlank()
                    || resolvedPlayer.name() == null || resolvedPlayer.name().isBlank()) {
                conflict("PUBG 계정을 확인하지 못했습니다. 다시 시도해 주세요.");
            }
            accounts.findByCommunityIdAndProviderAndExternalUserId(
                    communityId, ExternalAccountProvider.PUBG, resolvedPlayer.accountId()).ifPresent(claimed -> {
                if (!Objects.equals(claimed.getCommunityMember().getId(), member.getId())) {
                    conflict("해당 PUBG 계정은 다른 클랜원에게 연결되어 있습니다.");
                }
            });
            account = accounts.save(new CommunityMemberAccount(member, ExternalAccountProvider.PUBG,
                    resolvedPlayer.accountId(), resolvedPlayer.name()));
        }

        KillCompetitionParticipant participant = new KillCompetitionParticipant(
                competition, member, account.getExternalUserId(), account.getExternalUsername());
        competition.addParticipant(participant);
        participants.save(participant);
    }

    private KillCompetition requireDetail(Long communityId, Long id) {
        return competitions.findDetail(communityId, id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "킬내기를 찾을 수 없습니다."));
    }

    private KillCompetition requireForUpdate(Long communityId, Long id) {
        return competitions.findForUpdate(communityId, id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "킬내기를 찾을 수 없습니다."));
    }

    private boolean usable(CommunityMemberAccount account) {
        return account.getExternalUserId() != null && !account.getExternalUserId().isBlank()
                && account.getExternalUsername() != null && !account.getExternalUsername().isBlank();
    }

    private void conflict(String message) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public record JoinPreparation(Long memberId, String shard, String nickname, String accountId) {
        public boolean requiresLookup() {
            return accountId == null || accountId.isBlank();
        }
    }
}
