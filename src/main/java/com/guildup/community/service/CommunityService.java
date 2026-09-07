package com.guildup.community.service;

import com.guildup.community.domain.Community;
import com.guildup.community.repository.CommunityRepository;
import com.guildup.community.repository.CommunityUserRepository;
import com.guildup.community.repository.DiscordCommunityConnectionRepository;
import com.guildup.community.repository.CommunityGameRepository;
import com.guildup.community.repository.CommunityGameActivityRuleRepository;
import com.guildup.community.domain.CommunityUser;
import com.guildup.community.domain.CommunityUserRole;
import com.guildup.community.domain.CommunityGame;
import com.guildup.community.domain.CommunityGameActivityRule;
import com.guildup.community.domain.GameType;
import com.guildup.community.dto.MyCommunityResponse;
import com.guildup.community.dto.CommunityDashboardResponse;
import com.guildup.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 커뮤니티 생성과 조회를 담당하는 애플리케이션 서비스다. */
@Service
public class CommunityService {

    private final CommunityRepository communityRepository;

    private final CommunityUserRepository memberships;
    private final UserRepository users;
    private final CommunityAccessService access;
    private final DiscordCommunityConnectionRepository connections;
    private final CommunityGameRepository communityGames;
    private final CommunityGameActivityRuleRepository activityRules;

    public CommunityService(
            CommunityRepository communityRepository,
            CommunityUserRepository memberships, UserRepository users,
            CommunityAccessService access, DiscordCommunityConnectionRepository connections,
            CommunityGameRepository communityGames,
            CommunityGameActivityRuleRepository activityRules
    ) {
        this.communityRepository = communityRepository;
        this.memberships = memberships;
        this.users = users;
        this.access = access;
        this.connections = connections;
        this.communityGames = communityGames;
        this.activityRules = activityRules;
    }

    /** Community, 게임과 생성자의 OWNER 관계를 함께 커밋하거나 함께 롤백한다. */
    @Transactional
    public Community createCommunity(String name, GameType gameType, Long userId) {
        if (name == null || name.isBlank() || name.trim().length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Community name must be 1–255 characters");
        }
        if (gameType == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "게임을 선택해 주세요.");
        }
        var user = users.findById(userId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login user does not exist"));
        Community community = communityRepository.save(new Community(name.trim()));
        CommunityGame communityGame = communityGames.save(new CommunityGame(community, gameType));
        if (gameType.supportsRosterActivityRule()) {
            activityRules.save(CommunityGameActivityRule.defaultRule(communityGame));
        }
        memberships.save(new CommunityUser(community, user, CommunityUserRole.OWNER));
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
        Map<Long, GameType> gameTypes = communityGames.findByCommunityIdInOrderByIdAsc(
                        accessible.stream().map(item -> item.getCommunity().getId()).toList()
                ).stream()
                .collect(Collectors.toMap(
                        game -> game.getCommunity().getId(),
                        CommunityGame::getGameType,
                        (first, ignored) -> first
                ));
        return accessible.stream()
                .map(item -> MyCommunityResponse.from(
                        item, gameTypes.get(item.getCommunity().getId())
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public CommunityDashboardResponse getDashboard(Long userId, Long communityId) {
        var membership = access.requireAccess(userId, communityId);
        var community = membership.getCommunity();
        var connection = connections.findByCommunityId(communityId).orElse(null);
        GameType gameType = communityGames.findFirstByCommunityIdOrderByIdAsc(communityId)
                .map(CommunityGame::getGameType)
                .orElse(null);
        return new CommunityDashboardResponse(community.getId(), community.getName(), membership.getRole(),
                connection != null, connection == null ? null : connection.getDiscordGuildId(),
                connection == null ? null : connection.getDiscordGuildName(),
                connection == null ? null : connection.getLastMemberSyncedAt(), gameType,
                gameType == null ? null : gameType.getDisplayName());
    }
}
