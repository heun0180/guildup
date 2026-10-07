package com.guildup.community.service;

import com.guildup.community.domain.Community;
import com.guildup.community.repository.CommunityRepository;
import com.guildup.community.repository.CommunityUserRepository;
import com.guildup.community.repository.DiscordCommunityConnectionRepository;
import com.guildup.community.repository.CommunityGameRepository;
import com.guildup.community.repository.CommunityGameActivityRuleRepository;
import com.guildup.community.repository.CommunityDeletionStore;
import com.guildup.community.exception.CommunityNotFoundException;
import com.guildup.community.domain.CommunityUser;
import com.guildup.community.domain.CommunityUserRole;
import com.guildup.community.domain.CommunityGame;
import com.guildup.community.domain.CommunityGameActivityRule;
import com.guildup.community.domain.GameType;
import com.guildup.community.domain.GameCapability;
import com.guildup.community.dto.CommunityGameResponse;
import com.guildup.community.dto.MyCommunityResponse;
import com.guildup.community.dto.CommunityDashboardResponse;
import com.guildup.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 커뮤니티 생성과 조회를 담당하는 애플리케이션 서비스다. */
@Service
public class CommunityService {
    private static final Logger log = LoggerFactory.getLogger(CommunityService.class);

    private final CommunityRepository communityRepository;

    private final CommunityUserRepository memberships;
    private final UserRepository users;
    private final CommunityAccessService access;
    private final DiscordCommunityConnectionRepository connections;
    private final CommunityGameRepository communityGames;
    private final CommunityGameActivityRuleRepository activityRules;
    private final CommunityDeletionStore deletionStore;
    private final com.guildup.discord.oauth.service.DiscordBotInstallService botInstall;
    private final DiscordCommunityConnectionService discordConnections;
    private final CommunityNativeMembershipService nativeMemberships;

    public CommunityService(
            CommunityRepository communityRepository,
            CommunityUserRepository memberships, UserRepository users,
            CommunityAccessService access, DiscordCommunityConnectionRepository connections,
            CommunityGameRepository communityGames,
            CommunityGameActivityRuleRepository activityRules,
            CommunityDeletionStore deletionStore,
            com.guildup.discord.oauth.service.DiscordBotInstallService botInstall,
            DiscordCommunityConnectionService discordConnections,
            CommunityNativeMembershipService nativeMemberships
    ) {
        this.communityRepository = communityRepository;
        this.memberships = memberships;
        this.users = users;
        this.access = access;
        this.connections = connections;
        this.communityGames = communityGames;
        this.activityRules = activityRules;
        this.deletionStore = deletionStore;
        this.botInstall = botInstall;
        this.discordConnections = discordConnections;
        this.nativeMemberships = nativeMemberships;
    }

    /** HTTP 최종 생성 요청. 사용자 행 잠금과 영구 요청 키로 인스턴스/재시작 간 재시도를 보호한다. */
    @Transactional
    public Community createCommunity(com.guildup.community.dto.CommunityCreateRequest request,
                                     String requestId, Long userId) {
        if (requestId == null || !requestId.matches("[a-zA-Z0-9_-]{16,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "유효한 Idempotency-Key가 필요합니다.");
        }
        users.findForUpdate(userId).filter(com.guildup.user.domain.User::isActive).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login user does not exist"));
        String key = userId + ":" + requestId;
        String hash = creationHash(request);
        var previous = communityRepository.findByCreationRequestKey(key);
        if (previous.isPresent()) {
            if (!hash.equals(previous.get().getCreationRequestHash())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "같은 생성 요청 키로 다른 커뮤니티 정보를 제출할 수 없습니다.");
            }
            return previous.get();
        }
        var installation = request.discordInstallToken() == null ? null
                : botInstall.requireVerifiedCreationInstallation(userId, request.discordInstallToken());
        Community community = saveCommunity(request.name(), request.gameType(), userId, key, hash);
        if (installation != null) {
            discordConnections.connect(community.getId(), installation.guildId(), installation.guildName());
        }
        nativeMemberships.provision(memberships.findByCommunityIdAndUserId(community.getId(), userId).orElseThrow());
        return community;
    }

    private String creationHash(com.guildup.community.dto.CommunityCreateRequest request) {
        // 이름 길이를 함께 기록해 구분자 충돌을 피한다. 원문 설치 토큰은 저장하지 않는다.
        String name = request.name() == null ? "" : request.name().trim();
        String token = request.discordInstallToken();
        String input = name.length() + ":" + name + ":" + request.gameType() + ":"
                + (token == null ? "N" : "S" + token.length() + ":" + token);
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Community, 게임과 생성자의 OWNER 관계를 함께 커밋하거나 함께 롤백한다. */
    @Transactional
    public Community createCommunity(String name, GameType gameType, Long userId) {
        return saveCommunity(name, gameType, userId, null, null);
    }

    private Community saveCommunity(String name, GameType gameType, Long userId, String key, String hash) {
        if (name == null || name.isBlank() || name.trim().length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Community name must be 1–255 characters");
        }
        if (gameType == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "게임을 선택해 주세요.");
        }
        var user = users.findForUpdate(userId).filter(com.guildup.user.domain.User::isActive).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login user does not exist"));
        Community community = communityRepository.save(new Community(name.trim(), key, hash));
        CommunityGame communityGame = communityGames.save(new CommunityGame(community, gameType));
        if (gameType.supports(GameCapability.ACTIVITY)) {
            activityRules.save(CommunityGameActivityRule.defaultRule(communityGame));
        }
        memberships.save(new CommunityUser(community, user, CommunityUserRole.OWNER));
        CommunityOperationLogging.afterCommit(() -> log.info("Community created. communityId={}, userId={}, gameType={}",
                community.getId(), userId, gameType));
        return community;
    }

    /** 게임 선택 인자가 없던 기존 내부 호출의 호환성을 위해 카카오를 기본값으로 사용한다. */
    @Transactional
    public Community createCommunity(String name, Long userId) {
        return createCommunity(name, GameType.BATTLEGROUNDS_KAKAO, userId);
    }

    @Transactional(readOnly = true)
    public List<MyCommunityResponse> getCommunities(Long userId) {
        List<CommunityUser> accessible = memberships.findByUserIdOrderByCommunityIdAsc(userId);
        if (accessible.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> memberCounts = memberships.countMembersByCommunityIds(
                        accessible.stream().map(item -> item.getCommunity().getId()).toList()).stream()
                .collect(Collectors.toMap(CommunityUserRepository.MemberCount::getCommunityId,
                        CommunityUserRepository.MemberCount::getMemberCount));
        Map<Long, List<CommunityGameResponse>> gamesByCommunity = communityGames.findByCommunityIdInOrderByIdAsc(
                        accessible.stream().map(item -> item.getCommunity().getId()).toList()
                ).stream()
                .collect(Collectors.groupingBy(game -> game.getCommunity().getId(),
                        Collectors.mapping(CommunityGameResponse::from, Collectors.toList())));
        return accessible.stream()
                .map(item -> MyCommunityResponse.from(
                        item, gamesByCommunity.getOrDefault(item.getCommunity().getId(), List.of()),
                        memberCounts.getOrDefault(item.getCommunity().getId(), 0L)
                ))
                .toList();
    }

    @Transactional
    public String getInvitation(Long userId, Long communityId) {
        communityRepository.findForUpdate(communityId)
                .orElseThrow(() -> new CommunityNotFoundException(communityId));
        var membership = access.requireManagementAccess(userId, communityId);
        return membership.getCommunity().ensureInviteCode();
    }

    /** Discord 멤버십과 무관하게 초대 코드를 가진 로그인 사용자의 GuildUp 멤버십을 생성한다. */
    @Transactional
    public com.guildup.discord.oauth.dto.CommunityJoinResponse joinInvitation(Long userId, String code) {
        if (code == null || !code.trim().matches("(?i)[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "올바른 초대 코드를 입력해 주세요.");
        }
        var user = users.findForUpdate(userId).filter(com.guildup.user.domain.User::isActive).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login user does not exist"));
        var community = communityRepository.findByInviteCode(code.trim().toLowerCase(java.util.Locale.ROOT))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "초대 코드를 확인해 주세요."));
        communityRepository.findForUpdate(community.getId()).orElseThrow(() -> new CommunityNotFoundException(community.getId()));
        var membership = memberships.findByCommunityIdAndUserId(community.getId(), userId)
                .orElseGet(() -> memberships.save(new CommunityUser(community, user, CommunityUserRole.MEMBER)));
        if (connections.findByCommunityId(community.getId()).isEmpty()) nativeMemberships.provision(membership);
        else nativeMemberships.provisionIfDiscordAbsent(membership);
        return com.guildup.discord.oauth.dto.CommunityJoinResponse.from(membership);
    }

    @Transactional(readOnly = true)
    public CommunityDashboardResponse getDashboard(Long userId, Long communityId) {
        var membership = access.requireAccess(userId, communityId);
        var community = membership.getCommunity();
        var connection = connections.findByCommunityId(communityId).orElse(null);
        List<CommunityGameResponse> games = communityGames.findByCommunityIdOrderByIdAsc(communityId)
                .stream().map(CommunityGameResponse::from).toList();
        GameType gameType = games.isEmpty() ? null : games.get(0).gameType();
        return new CommunityDashboardResponse(community.getId(), community.getName(), membership.getRole(),
                connection != null, connection == null ? null : connection.getDiscordGuildId(),
                connection == null ? null : connection.getDiscordGuildName(),
                connection == null ? null : connection.getLastMemberSyncedAt(), gameType,
                gameType == null ? null : gameType.getDisplayName(), games);
    }

    /** 존재 여부와 OWNER 권한을 순서대로 확인한 뒤 커뮤니티 전용 데이터를 한 트랜잭션으로 삭제한다. */
    @Transactional
    public void deleteCommunity(Long userId, Long communityId) {
        communityRepository.findForUpdate(communityId)
                .orElseThrow(() -> new CommunityNotFoundException(communityId));
        CommunityUser membership = memberships.findByCommunityIdAndUserId(communityId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Community owner access denied"));
        if (membership.getRole() != CommunityUserRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Community owner access denied");
        }
        deletionStore.deleteCommunityData(communityId);
        CommunityOperationLogging.afterCommit(() -> log.info("Community deleted. communityId={}, userId={}", communityId, userId));
    }
}
