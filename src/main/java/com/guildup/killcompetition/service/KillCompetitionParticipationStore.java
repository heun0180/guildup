package com.guildup.killcompetition.service;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.domain.CommunityMember;
import com.guildup.community.domain.CommunityMemberAccount;
import com.guildup.community.repository.CommunityMemberAccountRepository;
import com.guildup.community.repository.CommunityMemberRepository;
import com.guildup.community.service.CommunityMemberPubgIdentityService;
import com.guildup.community.service.CurrentCommunityMemberService;
import com.guildup.killcompetition.domain.*;
import com.guildup.killcompetition.repository.KillCompetitionParticipantRepository;
import com.guildup.killcompetition.repository.KillCompetitionRepository;
import com.guildup.pubg.model.PubgPlayer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;
import java.util.Set;
import java.time.Clock;
import java.time.Instant;

/** PUBG 외부 호출 전후의 참가 조건 확인과 DB 반영을 짧은 트랜잭션으로 나눈다. */
@Service
public class KillCompetitionParticipationStore {
    private final KillCompetitionRepository competitions;
    private final KillCompetitionParticipantRepository participants;
    private final CurrentCommunityMemberService currentMembers;
    private final CommunityMemberAccountRepository accounts;
    private final CommunityMemberRepository memberRepository;
    private final CommunityMemberPubgIdentityService identities;
    private final KillCompetitionManagementAccess managementAccess;
    private final Clock clock;

    public KillCompetitionParticipationStore(KillCompetitionRepository competitions,
                                             KillCompetitionParticipantRepository participants,
                                             CurrentCommunityMemberService currentMembers,
                                             CommunityMemberAccountRepository accounts,
                                             CommunityMemberRepository memberRepository,
                                             CommunityMemberPubgIdentityService identities,
                                             KillCompetitionManagementAccess managementAccess,
                                             Clock clock) {
        this.competitions = competitions;
        this.participants = participants;
        this.currentMembers = currentMembers;
        this.accounts = accounts;
        this.memberRepository = memberRepository;
        this.identities = identities;
        this.managementAccess = managementAccess;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public JoinPreparation prepare(Long userId, Long communityId, Long competitionId) {
        KillCompetition competition = requireDetail(communityId, competitionId);
        requireJoinable(competition, clock.instant());
        CommunityMember member = currentMembers.require(userId, communityId);
        if (participants.existsByCompetitionIdAndCommunityMemberId(competitionId, member.getId())) {
            conflict("이미 참가한 킬내기입니다.");
        }
        var identity = identities.find(member, competition.getCommunityGame()).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                "Discord 닉네임에서 PUBG 인게임 닉네임을 확인할 수 없습니다. 커뮤니티 닉네임 형식을 확인해 주세요."));
        return new JoinPreparation(member.getId(), identity.shard(), identity.nickname(), identity.accountId());
    }

    @Transactional(readOnly = true)
    public JoinPreparation prepareDirect(Long userId, Long communityId, Long competitionId, Long memberId) {
        KillCompetition competition = requireDetail(communityId, competitionId);
        managementAccess.requireCanManage(userId, communityId, competition);
        requireMutable(competition, clock.instant());
        CommunityMember member = memberRepository.findById(memberId).filter(candidate ->
                        Objects.equals(candidate.getCommunity().getId(), communityId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "클랜원을 찾을 수 없습니다."));
        if (participants.existsByCompetitionIdAndCommunityMemberId(competitionId, memberId)) conflict("이미 신청 또는 참가한 클랜원입니다.");
        var identity = identities.find(member, competition.getCommunityGame()).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                "선택한 클랜원의 PUBG 닉네임을 확인할 수 없습니다."));
        return new JoinPreparation(member.getId(), identity.shard(), identity.nickname(), identity.accountId());
    }

    @Transactional
    public void commit(Long userId, Long communityId, Long competitionId,
                       JoinPreparation preparation, PubgPlayer resolvedPlayer) {
        KillCompetition competition = requireForUpdate(communityId, competitionId);
        requireJoinable(competition, clock.instant());
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

        addParticipant(competition, member, account,
                competition.getStatus() == KillCompetitionStatus.IN_PROGRESS
                        ? KillCompetitionParticipationStatus.PENDING
                        : KillCompetitionParticipationStatus.APPROVED,
                null);
    }

    @Transactional
    public void commitDirect(Long userId, Long communityId, Long competitionId, JoinPreparation preparation,
                             PubgPlayer resolvedPlayer, Long teamId) {
        KillCompetition competition = requireForUpdate(communityId, competitionId);
        managementAccess.requireCanManage(userId, communityId, competition);
        Instant now = clock.instant();
        requireMutable(competition, now);
        CommunityMember member = memberRepository.findById(preparation.memberId()).filter(candidate ->
                        Objects.equals(candidate.getCommunity().getId(), communityId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "클랜원을 찾을 수 없습니다."));
        if (participants.existsByCompetitionIdAndCommunityMemberId(competitionId, member.getId())) conflict("이미 신청 또는 참가한 클랜원입니다.");
        CommunityMemberAccount account = resolveAccount(communityId, member, resolvedPlayer);
        KillCompetitionTeam team = resolveTeam(competition, teamId,
                competition.getStatus() == KillCompetitionStatus.IN_PROGRESS);
        addParticipant(competition, member, account, KillCompetitionParticipationStatus.APPROVED,
                competition.getStatus() == KillCompetitionStatus.IN_PROGRESS ? now : null, team);
    }

    private void addParticipant(KillCompetition competition, CommunityMember member, CommunityMemberAccount account,
                                KillCompetitionParticipationStatus status, Instant eligibleFrom) {
        addParticipant(competition, member, account, status, eligibleFrom, null);
    }

    private void addParticipant(KillCompetition competition, CommunityMember member, CommunityMemberAccount account,
                                KillCompetitionParticipationStatus status, Instant eligibleFrom, KillCompetitionTeam team) {
        KillCompetitionParticipant participant = new KillCompetitionParticipant(
                competition, member, account.getExternalUserId(), account.getExternalUsername(), status);
        if (status == KillCompetitionParticipationStatus.APPROVED && eligibleFrom != null) participant.approve(eligibleFrom, team);
        else participant.assignTeam(team);
        competition.addParticipant(participant);
        participants.save(participant);
    }

    private CommunityMemberAccount resolveAccount(Long communityId, CommunityMember member, PubgPlayer resolvedPlayer) {
        CommunityMemberAccount account = accounts.findByCommunityMemberIdAndProvider(
                member.getId(), ExternalAccountProvider.PUBG).filter(this::usable).orElse(null);
        if (account != null) return account;
        if (resolvedPlayer == null || resolvedPlayer.accountId() == null || resolvedPlayer.accountId().isBlank()
                || resolvedPlayer.name() == null || resolvedPlayer.name().isBlank()) conflict("PUBG 계정을 확인하지 못했습니다. 다시 시도해 주세요.");
        accounts.findByCommunityIdAndProviderAndExternalUserId(
                communityId, ExternalAccountProvider.PUBG, resolvedPlayer.accountId()).ifPresent(claimed -> {
            if (!Objects.equals(claimed.getCommunityMember().getId(), member.getId())) conflict("해당 PUBG 계정은 다른 클랜원에게 연결되어 있습니다.");
        });
        return accounts.save(new CommunityMemberAccount(member, ExternalAccountProvider.PUBG,
                resolvedPlayer.accountId(), resolvedPlayer.name()));
    }

    private KillCompetitionTeam resolveTeam(KillCompetition competition, Long teamId, boolean required) {
        if (competition.getGameMode() == KillCompetitionGameMode.SOLO) return null;
        if (teamId == null) {
            if (required) conflict("진행 중인 팀 킬내기에는 팀을 선택해야 합니다.");
            return null;
        }
        return competition.getTeams().stream().filter(team -> Objects.equals(team.getId(), teamId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "올바른 팀을 선택해 주세요."));
    }

    private void requireJoinable(KillCompetition competition, Instant now) {
        requireMutable(competition, now);
        if (!competition.isRecruitmentOpen()) conflict("참가 신청이 닫혀 있습니다.");
        if (!Set.of(KillCompetitionStatus.RECRUITING, KillCompetitionStatus.READY, KillCompetitionStatus.IN_PROGRESS)
                .contains(competition.getStatus())) conflict("현재 참가 신청을 받을 수 없습니다.");
    }

    private void requireMutable(KillCompetition competition, Instant now) {
        if (!now.isBefore(competition.getEndsAt()) || Set.of(KillCompetitionStatus.RESULT_PENDING,
                KillCompetitionStatus.COMPLETED, KillCompetitionStatus.CANCELLED).contains(competition.getStatus())) {
            conflict("종료되었거나 결과 발표 중인 킬내기는 참가자를 변경할 수 없습니다.");
        }
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
