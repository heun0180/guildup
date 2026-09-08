package com.guildup.community.service;

import com.guildup.community.domain.CommunityUser;
import com.guildup.community.domain.CommunityUserRole;
import com.guildup.community.exception.AlreadyCommunityMemberException;
import com.guildup.community.exception.DiscordCommunityConnectionNotFoundException;
import com.guildup.community.repository.CommunityRepository;
import com.guildup.community.repository.CommunityUserRepository;
import com.guildup.community.repository.DiscordCommunityConnectionRepository;
import com.guildup.discord.oauth.dto.CommunityJoinResponse;
import com.guildup.discord.oauth.dto.DiscordGuildSelectionResponse;
import com.guildup.discord.oauth.store.DiscordOAuthSessionStore;
import com.guildup.user.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;

/** Discord 관리 권한이 확인된 사용자의 기존 커뮤니티 조회와 참여를 담당한다. */
@Service
public class CommunityMembershipService {
    private static final Duration NEW_COMMUNITY_WINDOW = Duration.ofHours(1);

    private final CommunityAccessService access;
    private final DiscordOAuthSessionStore oauthResults;
    private final DiscordCommunityConnectionRepository connections;
    private final CommunityUserRepository memberships;
    private final UserRepository users;
    private final CommunityRepository communities;

    public CommunityMembershipService(
            CommunityAccessService access,
            DiscordOAuthSessionStore oauthResults,
            DiscordCommunityConnectionRepository connections,
            CommunityUserRepository memberships,
            UserRepository users,
            CommunityRepository communities
    ) {
        this.access = access;
        this.oauthResults = oauthResults;
        this.connections = connections;
        this.memberships = memberships;
        this.users = users;
        this.communities = communities;
    }

    /** OAuth가 증명한 관리 가능 서버가 GuildUp에 등록되어 있는지 조회한다. */
    @Transactional(readOnly = true)
    public DiscordGuildSelectionResponse inspect(
            Long userId,
            Long sourceCommunityId,
            String oauthResultId,
            String guildId
    ) {
        access.requireManagementAccess(userId, sourceCommunityId);
        var selectedGuild = oauthResults.getSelectedGuild(sourceCommunityId, oauthResultId, guildId);
        return connections.findByDiscordGuildId(selectedGuild.id())
                .map(connection -> {
                    var community = connection.getCommunity();
                    return new DiscordGuildSelectionResponse(
                            true,
                            community.getId(),
                            community.getName(),
                            memberships.existsByCommunityIdAndUserId(community.getId(), userId)
                    );
                })
                .orElseGet(DiscordGuildSelectionResponse::available);
    }

    /** OAuth의 Discord 관리 권한을 다시 검증하고 기존 커뮤니티에 ADMIN으로 참여시킨다. */
    @Transactional
    public CommunityJoinResponse join(
            Long userId,
            Long sourceCommunityId,
            String oauthResultId,
            String guildId,
            boolean discardSourceCommunity
    ) {
        CommunityUser sourceMembership = access.requireManagementAccess(userId, sourceCommunityId);
        var selectedGuild = oauthResults.consumeSelectedGuild(sourceCommunityId, oauthResultId, guildId);
        var connection = connections.findByDiscordGuildId(selectedGuild.id())
                .orElseThrow(() -> new DiscordCommunityConnectionNotFoundException(sourceCommunityId));
        Long targetCommunityId = connection.getCommunity().getId();

        var existingMembership = memberships.findByCommunityIdAndUserId(targetCommunityId, userId);
        if (existingMembership.isPresent() && discardSourceCommunity) {
            discardNewUnconnectedSource(sourceMembership, targetCommunityId);
            return CommunityJoinResponse.from(existingMembership.get());
        }
        if (existingMembership.isPresent()) {
            throw new AlreadyCommunityMemberException(targetCommunityId);
        }
        var user = users.findById(userId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login user does not exist"));
        try {
            CommunityUser joined = memberships.saveAndFlush(new CommunityUser(
                    connection.getCommunity(), user, CommunityUserRole.ADMIN
            ));
            discardNewUnconnectedSource(sourceMembership, targetCommunityId);
            return CommunityJoinResponse.from(joined);
        } catch (DataIntegrityViolationException exception) {
            throw new AlreadyCommunityMemberException(targetCommunityId);
        }
    }

    /** 방금 만든 빈 연결용 Community만 정리해 중복 시도 뒤 고아 Community가 남지 않게 한다. */
    private void discardNewUnconnectedSource(CommunityUser sourceMembership, Long targetCommunityId) {
        var source = sourceMembership.getCommunity();
        if (source.getId().equals(targetCommunityId)
                || sourceMembership.getRole() != CommunityUserRole.OWNER
                || memberships.countByCommunityId(source.getId()) != 1
                || connections.findByCommunityId(source.getId()).isPresent()
                || source.getCreatedAt().isBefore(Instant.now().minus(NEW_COMMUNITY_WINDOW))) {
            return;
        }
        communities.delete(source);
        communities.flush();
    }
}
