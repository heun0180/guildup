package com.guildup.community.service;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.domain.Community;
import com.guildup.community.domain.CommunityGameNicknameRule;
import com.guildup.community.domain.GameType;
import com.guildup.community.dto.GameNicknameExtractionStatus;
import com.guildup.community.dto.GameNicknameMemberPreviewResponse;
import com.guildup.community.dto.GameNicknameRulePreviewResponse;
import com.guildup.community.dto.GameNicknameRuleResponse;
import com.guildup.community.repository.CommunityGameNicknameRuleRepository;
import com.guildup.community.repository.CommunityGameRepository;
import com.guildup.community.service.nickname.GameNicknameRuleInferenceService;
import com.guildup.community.service.nickname.NicknameRuleCandidate;
import com.guildup.discord.service.DiscordGuildService;
import com.guildup.discord.service.DiscordMemberService;
import com.guildup.user.repository.UserExternalAccountRepository;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Discord 닉네임 샘플 분석, 전체 멤버 미리보기와 규칙 저장을 담당한다. */
@Service
public class CommunityGameNicknameRuleService {

    private final CommunityAccessService accessService;
    private final DiscordCommunityConnectionService connectionService;
    private final UserExternalAccountRepository userAccountRepository;
    private final CommunityGameNicknameRuleRepository ruleRepository;
    private final CommunityGameRepository communityGameRepository;
    private final DiscordGuildService discordGuildService;
    private final DiscordMemberService discordMemberService;
    private final GameNicknameRuleInferenceService inferenceService;

    public CommunityGameNicknameRuleService(
            CommunityAccessService accessService,
            DiscordCommunityConnectionService connectionService,
            UserExternalAccountRepository userAccountRepository,
            CommunityGameNicknameRuleRepository ruleRepository,
            CommunityGameRepository communityGameRepository,
            DiscordGuildService discordGuildService,
            DiscordMemberService discordMemberService,
            GameNicknameRuleInferenceService inferenceService
    ) {
        this.accessService = accessService;
        this.connectionService = connectionService;
        this.userAccountRepository = userAccountRepository;
        this.ruleRepository = ruleRepository;
        this.communityGameRepository = communityGameRepository;
        this.discordGuildService = discordGuildService;
        this.discordMemberService = discordMemberService;
        this.inferenceService = inferenceService;
    }

    @Transactional(readOnly = true)
    public GameNicknameRuleResponse getRule(Long userId, Long communityId) {
        accessService.requireManagementAccess(userId, communityId);
        GameType gameType = requireGameType(communityId);
        DiscordContext context = loadDiscordContext(userId, communityId);
        return ruleRepository.findByCommunityIdAndGameType(communityId, gameType)
                .map(rule -> toResponse(rule, context.currentDiscordNickname()))
                .orElseGet(() -> new GameNicknameRuleResponse(
                        false, gameType.name(), context.currentDiscordNickname(),
                        null, null, null, null
                ));
    }

    @Transactional(readOnly = true)
    public GameNicknameRulePreviewResponse preview(Long userId, Long communityId, String enteredGameNickname) {
        accessService.requireManagementAccess(userId, communityId);
        return analyzeAuthorized(userId, communityId, requireGameType(communityId), enteredGameNickname).response();
    }

    @Transactional(readOnly = true)
    public GameNicknameRulePreviewResponse previewSavedRule(Long userId, Long communityId) {
        accessService.requireManagementAccess(userId, communityId);
        GameType gameType = requireGameType(communityId);
        CommunityGameNicknameRule rule = ruleRepository
                .findByCommunityIdAndGameType(communityId, gameType)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "저장된 인게임 닉네임 규칙이 없습니다."));
        DiscordContext context = loadDiscordContext(userId, communityId);
        return createPreview(context, gameType, rule.getSampleGameNickname(), toCandidate(rule));
    }

    @Transactional
    public GameNicknameRuleResponse save(Long userId, Long communityId, String enteredGameNickname) {
        Community community = accessService.requireManagementAccess(userId, communityId).getCommunity();
        GameType gameType = requireGameType(communityId);
        Analysis analysis = analyzeAuthorized(userId, communityId, gameType, enteredGameNickname);
        NicknameRuleCandidate candidate = analysis.candidate();
        CommunityGameNicknameRule rule;
        var existingRule = ruleRepository.findByCommunityIdAndGameType(communityId, gameType);
        if (existingRule.isPresent()) {
            rule = existingRule.get();
            rule.configure(
                    candidate.strategyType(), candidate.delimiterType(), candidate.segmentIndex(),
                    candidate.fromEnd(), candidate.expectedSegmentCount(),
                    analysis.response().currentDiscordNickname(), analysis.response().enteredGameNickname()
            );
        } else {
            rule = new CommunityGameNicknameRule(
                    community, gameType,
                    candidate.strategyType(), candidate.delimiterType(), candidate.segmentIndex(),
                    candidate.fromEnd(), candidate.expectedSegmentCount(),
                    analysis.response().currentDiscordNickname(), analysis.response().enteredGameNickname()
            );
        }
        CommunityGameNicknameRule saved = ruleRepository.save(rule);
        return toResponse(saved, analysis.response().currentDiscordNickname());
    }

    private Analysis analyzeAuthorized(
            Long userId,
            Long communityId,
            GameType gameType,
            String enteredGameNickname
    ) {
        DiscordContext context = loadDiscordContext(userId, communityId);
        String gameNickname = inferenceService.normalizeGameNickname(enteredGameNickname);
        List<NicknameRuleCandidate> candidates = inferenceService.infer(
                context.currentDiscordNickname(), gameNickname
        );
        List<String> displayNames = context.members().stream()
                .map(discordMemberService::getDisplayName)
                .toList();
        NicknameRuleCandidate selected = inferenceService.selectBest(candidates, displayNames);
        return new Analysis(selected, createPreview(context, gameType, gameNickname, selected));
    }

    private GameNicknameRulePreviewResponse createPreview(
            DiscordContext context,
            GameType gameType,
            String gameNickname,
            NicknameRuleCandidate candidate
    ) {
        List<GameNicknameMemberPreviewResponse> previews = context.members().stream()
                .map(member -> toMemberPreview(member, candidate))
                .toList();
        int successful = (int) previews.stream()
                .filter(preview -> preview.status() == GameNicknameExtractionStatus.SUCCESS)
                .count();
        int total = previews.size();
        double successRate = total == 0 ? 0.0 : Math.round(successful * 1000.0 / total) / 10.0;
        return new GameNicknameRulePreviewResponse(
                gameType.name(),
                context.currentDiscordNickname(),
                gameNickname,
                inferenceService.describe(candidate),
                total,
                successful,
                total - successful,
                successRate,
                previews
        );
    }

    private GameType requireGameType(Long communityId) {
        return communityGameRepository.findFirstByCommunityIdOrderByIdAsc(communityId)
                .map(game -> game.getGameType())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "커뮤니티에서 사용할 게임이 설정되지 않았습니다."));
    }

    private DiscordContext loadDiscordContext(Long userId, Long communityId) {
        var connection = connectionService.getRequiredConnection(communityId);
        Guild guild = discordGuildService.getGuildById(connection.getDiscordGuildId());
        List<Member> members = discordMemberService.getMembers(guild).stream()
                .filter(member -> !member.getUser().isBot())
                .toList();
        String discordUserId = userAccountRepository
                .findByUserIdAndProvider(userId, ExternalAccountProvider.DISCORD)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "현재 로그인 사용자의 Discord 계정 정보를 찾을 수 없습니다."))
                .getExternalUserId();
        Member currentMember = members.stream()
                .filter(member -> member.getUser().getId().equals(discordUserId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "연결된 Discord 서버에서 현재 로그인 사용자를 찾을 수 없습니다."));
        return new DiscordContext(members, discordMemberService.getDisplayName(currentMember));
    }

    private GameNicknameMemberPreviewResponse toMemberPreview(Member member, NicknameRuleCandidate candidate) {
        String displayName = discordMemberService.getDisplayName(member);
        String extracted = inferenceService.extract(candidate, displayName).orElse(null);
        return new GameNicknameMemberPreviewResponse(
                member.getUser().getId(),
                member.getUser().getName(),
                displayName,
                extracted,
                extracted == null ? GameNicknameExtractionStatus.NEEDS_REVIEW : GameNicknameExtractionStatus.SUCCESS
        );
    }

    private GameNicknameRuleResponse toResponse(CommunityGameNicknameRule rule, String currentDiscordNickname) {
        NicknameRuleCandidate candidate = toCandidate(rule);
        return new GameNicknameRuleResponse(
                true,
                rule.getGameType().name(),
                currentDiscordNickname,
                inferenceService.describe(candidate),
                rule.getSampleDiscordNickname(),
                rule.getSampleGameNickname(),
                rule.getUpdatedAt()
        );
    }

    private NicknameRuleCandidate toCandidate(CommunityGameNicknameRule rule) {
        return new NicknameRuleCandidate(
                rule.getStrategyType(), rule.getDelimiterType(), rule.getSegmentIndex(),
                rule.isFromEnd(), rule.getExpectedSegmentCount()
        );
    }

    private record DiscordContext(List<Member> members, String currentDiscordNickname) {}
    private record Analysis(NicknameRuleCandidate candidate, GameNicknameRulePreviewResponse response) {}
}
