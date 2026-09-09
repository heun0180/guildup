package com.guildup.community.service;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.domain.CommunityGame;
import com.guildup.community.domain.CommunityGameNicknameRule;
import com.guildup.community.domain.CommunityMember;
import com.guildup.community.domain.CommunityMemberAccount;
import com.guildup.community.domain.CommunityMemberStatus;
import com.guildup.community.dto.TeamGenerationRequest;
import com.guildup.community.dto.TeamGenerationResponse;
import com.guildup.community.dto.TeamMakerParticipantResponse;
import com.guildup.community.dto.TeamMakerParticipantsResponse;
import com.guildup.community.dto.TeamRebalanceRequest;
import com.guildup.community.repository.CommunityGameNicknameRuleRepository;
import com.guildup.community.repository.CommunityGameRepository;
import com.guildup.community.repository.CommunityMemberAccountRepository;
import com.guildup.community.repository.CommunityMemberRepository;
import com.guildup.community.service.nickname.GameNicknameRuleInferenceService;
import com.guildup.community.service.nickname.NicknameRuleCandidate;
import com.guildup.pubg.model.PubgPlayer;
import com.guildup.pubg.model.PubgSeasonStats;
import com.guildup.pubg.service.PubgPlayerService;
import com.guildup.pubg.service.PubgSeasonService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 운영진 팀 만들기 화면의 참가자 조회, 시즌 통계 계산과 팀 편성을 조율한다. */
@Service
public class CommunityTeamMakerService {
    private final CommunityAccessService accessService;
    private final CommunityMemberRepository memberRepository;
    private final CommunityMemberAccountRepository accountRepository;
    private final CommunityGameRepository gameRepository;
    private final CommunityGameNicknameRuleRepository nicknameRuleRepository;
    private final GameNicknameRuleInferenceService nicknameInferenceService;
    private final PubgPlayerService playerService;
    private final PubgSeasonService seasonService;
    private final TeamBalanceService balanceService;

    public CommunityTeamMakerService(
            CommunityAccessService accessService,
            CommunityMemberRepository memberRepository,
            CommunityMemberAccountRepository accountRepository,
            CommunityGameRepository gameRepository,
            CommunityGameNicknameRuleRepository nicknameRuleRepository,
            GameNicknameRuleInferenceService nicknameInferenceService,
            PubgPlayerService playerService,
            PubgSeasonService seasonService,
            TeamBalanceService balanceService
    ) {
        this.accessService = accessService;
        this.memberRepository = memberRepository;
        this.accountRepository = accountRepository;
        this.gameRepository = gameRepository;
        this.nicknameRuleRepository = nicknameRuleRepository;
        this.nicknameInferenceService = nicknameInferenceService;
        this.playerService = playerService;
        this.seasonService = seasonService;
        this.balanceService = balanceService;
    }

    @Transactional(readOnly = true)
    public TeamMakerParticipantsResponse getParticipants(Long userId, Long communityId) {
        accessService.requireManagementAccess(userId, communityId);
        CommunityGame game = requireGame(communityId);
        return new TeamMakerParticipantsResponse(loadCandidates(communityId, game).stream()
                .map(candidate -> TeamMakerParticipantResponse.selectable(
                        candidate.member().getId(), candidate.member().getNickname(), candidate.pubgNickname()
                ))
                .toList());
    }

    @Transactional(readOnly = true)
    public TeamGenerationResponse generate(Long userId, Long communityId, TeamGenerationRequest request) {
        accessService.requireManagementAccess(userId, communityId);
        validateGenerationRequest(request);
        CommunityGame game = requireGame(communityId);
        Map<Long, Candidate> candidates = loadCandidates(communityId, game).stream()
                .collect(Collectors.toMap(candidate -> candidate.member().getId(), Function.identity(),
                        (first, ignored) -> first, LinkedHashMap::new));
        List<Candidate> selected = new ArrayList<>();
        for (Long memberId : new LinkedHashSet<>(request.participantIds())) {
            Candidate candidate = candidates.get(memberId);
            if (candidate == null) {
                throw badRequest("선택한 참가자를 현재 팀 만들기 목록에서 찾을 수 없습니다.");
            }
            selected.add(candidate);
        }

        String shard = game.getGameType().getPubgShard();
        resolveAccountIds(shard, selected);
        PubgSeasonService.SeasonPair seasons = seasonService.getCurrentAndPrevious(shard);
        List<String> selectedSeasonIds = new ArrayList<>();
        if (request.currentSeason()) selectedSeasonIds.add(seasons.currentSeasonId());
        if (request.previousSeason()) selectedSeasonIds.add(seasons.previousSeasonId());

        Map<String, PubgSeasonStats> statsByAccountId = seasonService.getCombinedStats(
                shard,
                selected.stream().map(Candidate::accountId).filter(java.util.Objects::nonNull).distinct().toList(),
                selectedSeasonIds
        );

        List<TeamMakerParticipantResponse> measured = new ArrayList<>();
        List<TeamMakerParticipantResponse> missing = new ArrayList<>();
        for (Candidate candidate : selected) {
            TeamMakerParticipantResponse response;
            if (candidate.accountId() == null) {
                response = missing(candidate);
            } else {
                PubgSeasonStats stats = statsByAccountId.getOrDefault(
                        candidate.accountId(), PubgSeasonStats.empty(candidate.accountId())
                );
                response = stats.roundsPlayed() == 0
                        ? missing(candidate)
                        : measured(candidate, stats);
            }
            if (response.averageDamage() == null) missing.add(response);
            else measured.add(response);
        }
        if (!missing.isEmpty()) {
            return new TeamGenerationResponse(
                    seasons.currentSeasonId(), seasons.previousSeasonId(), selectedSeasonIds,
                    measured, missing, List.of(), null
            );
        }
        TeamBalanceService.BalanceResult result = balanceService.balance(
                measured, request.maxMembersPerTeam(), seed(request.seed())
        );
        return new TeamGenerationResponse(
                seasons.currentSeasonId(), seasons.previousSeasonId(), selectedSeasonIds,
                measured, List.of(), result.teams(), result.summary()
        );
    }

    public TeamGenerationResponse rebalance(Long userId, Long communityId, TeamRebalanceRequest request) {
        accessService.requireManagementAccess(userId, communityId);
        if (request == null || request.participants() == null || request.participants().isEmpty()) {
            throw badRequest("다시 생성할 참가자 통계가 없습니다.");
        }
        validateTeamSize(request.maxMembersPerTeam());
        List<TeamMakerParticipantResponse> participants = request.participants().stream()
                .map(this::validateAndRecalculate)
                .toList();
        TeamBalanceService.BalanceResult result = balanceService.balance(
                participants, request.maxMembersPerTeam(), seed(request.seed())
        );
        return new TeamGenerationResponse(
                null, null, List.of(), participants, List.of(), result.teams(), result.summary()
        );
    }

    private List<Candidate> loadCandidates(Long communityId, CommunityGame game) {
        List<CommunityMember> members = memberRepository.findByCommunityIdAndStatusOrderByIdAsc(
                communityId, CommunityMemberStatus.ACTIVE
        );
        Map<Long, CommunityMemberAccount> pubgAccounts = accountRepository
                .findByCommunityIdAndProvider(communityId, ExternalAccountProvider.PUBG).stream()
                .collect(Collectors.toMap(
                        account -> account.getCommunityMember().getId(), Function.identity()
                ));
        CommunityGameNicknameRule rule = nicknameRuleRepository
                .findByCommunityIdAndGameType(communityId, game.getGameType()).orElse(null);
        NicknameRuleCandidate ruleCandidate = rule == null ? null : new NicknameRuleCandidate(
                rule.getStrategyType(), rule.getDelimiterType(), rule.getSegmentIndex(),
                rule.isFromEnd(), rule.getExpectedSegmentCount()
        );

        List<Candidate> result = new ArrayList<>();
        for (CommunityMember member : members) {
            CommunityMemberAccount account = pubgAccounts.get(member.getId());
            String pubgNickname = account == null ? null : account.getExternalUsername();
            if ((pubgNickname == null || pubgNickname.isBlank()) && ruleCandidate != null) {
                pubgNickname = nicknameInferenceService.extract(ruleCandidate, member.getNickname()).orElse(null);
            }
            if (pubgNickname != null && !pubgNickname.isBlank()) {
                result.add(new Candidate(
                        member, pubgNickname.trim(), account == null ? null : account.getExternalUserId()
                ));
            }
        }
        return result;
    }

    private void resolveAccountIds(String shard, List<Candidate> selected) {
        List<String> names = selected.stream().filter(candidate -> candidate.accountId() == null)
                .map(Candidate::pubgNickname).distinct().toList();
        Map<String, PubgPlayer> players = playerService.findByNames(shard, names).stream()
                .filter(player -> player.name() != null && player.accountId() != null)
                .collect(Collectors.toMap(player -> normalize(player.name()), Function.identity(),
                        (first, ignored) -> first, LinkedHashMap::new));
        for (int index = 0; index < selected.size(); index++) {
            Candidate candidate = selected.get(index);
            if (candidate.accountId() != null) continue;
            PubgPlayer player = players.get(normalize(candidate.pubgNickname()));
            if (player != null) selected.set(index, candidate.withAccountId(player.accountId()));
        }
    }

    private TeamMakerParticipantResponse measured(Candidate candidate, PubgSeasonStats stats) {
        return new TeamMakerParticipantResponse(
                candidate.member().getId(), candidate.member().getNickname(), candidate.pubgNickname(),
                stats.damageDealt(), stats.roundsPlayed(), round(stats.damageDealt() / stats.roundsPlayed())
        );
    }

    private TeamMakerParticipantResponse missing(Candidate candidate) {
        return new TeamMakerParticipantResponse(
                candidate.member().getId(), candidate.member().getNickname(), candidate.pubgNickname(),
                null, 0L, null
        );
    }

    private TeamMakerParticipantResponse validateAndRecalculate(TeamMakerParticipantResponse participant) {
        if (participant == null || participant.memberId() == null
                || participant.discordNickname() == null || participant.discordNickname().isBlank()
                || participant.pubgNickname() == null || participant.pubgNickname().isBlank()
                || participant.damageDealt() == null || participant.damageDealt() < 0
                || participant.roundsPlayed() == null || participant.roundsPlayed() <= 0) {
            throw badRequest("다시 생성할 참가자 통계가 올바르지 않습니다.");
        }
        return new TeamMakerParticipantResponse(
                participant.memberId(), participant.discordNickname(), participant.pubgNickname(),
                participant.damageDealt(), participant.roundsPlayed(),
                round(participant.damageDealt() / participant.roundsPlayed())
        );
    }

    private void validateGenerationRequest(TeamGenerationRequest request) {
        if (request == null || request.participantIds() == null || request.participantIds().isEmpty()) {
            throw badRequest("참가자를 한 명 이상 선택해 주세요.");
        }
        if (!request.currentSeason() && !request.previousSeason()) {
            throw badRequest("현재 시즌과 전 시즌 중 하나 이상을 선택해 주세요.");
        }
        if (request.participantIds().stream().anyMatch(java.util.Objects::isNull)) {
            throw badRequest("참가자 정보가 올바르지 않습니다.");
        }
        validateTeamSize(request.maxMembersPerTeam());
    }

    private void validateTeamSize(int size) {
        if (size < 1 || size > 10) throw badRequest("팀당 최대 인원은 1명부터 10명까지 선택할 수 있습니다.");
    }

    private CommunityGame requireGame(Long communityId) {
        return gameRepository.findFirstByCommunityIdOrderByIdAsc(communityId)
                .orElseThrow(() -> badRequest("커뮤니티의 PUBG 플랫폼 설정을 찾을 수 없습니다."));
    }

    private long seed(Long requested) { return requested == null ? System.nanoTime() : requested; }
    private double round(double value) { return Math.round(value * 10.0) / 10.0; }
    private String normalize(String value) { return value.toLowerCase(Locale.ROOT); }
    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private record Candidate(CommunityMember member, String pubgNickname, String accountId) {
        Candidate withAccountId(String value) { return new Candidate(member, pubgNickname, value); }
    }
}
