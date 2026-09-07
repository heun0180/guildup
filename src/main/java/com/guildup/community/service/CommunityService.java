package com.guildup.community.service;

import com.guildup.community.domain.Community;
import com.guildup.community.domain.DiscordCommunityConnection;
import com.guildup.community.exception.CommunityNotFoundException;
import com.guildup.community.repository.CommunityRepository;
import com.guildup.community.repository.CommunityUserRepository;
import com.guildup.community.repository.DiscordCommunityConnectionRepository;
import com.guildup.community.domain.CommunityUser;
import com.guildup.community.domain.CommunityUserRole;
import com.guildup.community.dto.MyCommunityResponse;
import com.guildup.community.dto.CommunityDashboardResponse;
import com.guildup.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 커뮤니티 생성과 Discord 연결의 멤버 역할 설정을 담당하는 애플리케이션 서비스다. */
@Service
public class CommunityService {

    private final CommunityRepository communityRepository;
    private final DiscordCommunityConnectionService connectionService;

    private final CommunityUserRepository memberships;
    private final UserRepository users;
    private final CommunityAccessService access;
    private final DiscordCommunityConnectionRepository connections;

    public CommunityService(
            CommunityRepository communityRepository,
            DiscordCommunityConnectionService connectionService,
            CommunityUserRepository memberships, UserRepository users,
            CommunityAccessService access, DiscordCommunityConnectionRepository connections
    ) {
        this.communityRepository = communityRepository;
        this.connectionService = connectionService;
        this.memberships = memberships;
        this.users = users;
        this.access = access;
        this.connections = connections;
    }

    /** Community와 생성자의 OWNER 관계를 함께 커밋하거나 함께 롤백한다. */
    @Transactional
    public Community createCommunity(String name, Long userId) {
        if (name == null || name.isBlank() || name.trim().length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Community name must be 1–255 characters");
        }
        var user = users.findById(userId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login user does not exist"));
        Community community = communityRepository.save(new Community(name.trim()));
        memberships.save(new CommunityUser(community, user, CommunityUserRole.OWNER));
        return community;
    }

    @Transactional(readOnly = true)
    public List<MyCommunityResponse> getCommunities(Long userId) {
        return memberships.findByUserIdOrderByCommunityIdAsc(userId).stream()
                .map(MyCommunityResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public CommunityDashboardResponse getDashboard(Long userId, Long communityId) {
        var membership = access.requireAccess(userId, communityId);
        var community = membership.getCommunity();
        var connection = connections.findByCommunityId(communityId).orElse(null);
        return new CommunityDashboardResponse(community.getId(), community.getName(), membership.getRole(),
                connection != null, connection == null ? null : connection.getDiscordGuildId(),
                connection == null ? null : connection.getDiscordGuildName());
    }

    /** 연결된 Discord 서버의 멤버 역할 ID를 설정한다. */
    @Transactional
    public Community configureDiscordMemberRole(Long communityId, String roleId) {
        if (roleId == null || roleId.isBlank()) {
            throw new IllegalArgumentException("Discord member role ID must not be blank");
        }

        DiscordCommunityConnection connection = connectionService.getRequiredConnection(communityId);
        connection.configureMemberRole(roleId.trim());
        return communityRepository.findById(communityId)
                .orElseThrow(() -> new CommunityNotFoundException(communityId));
    }
}
