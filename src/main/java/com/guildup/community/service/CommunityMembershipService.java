package com.guildup.community.service;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.domain.CommunityUser;
import com.guildup.community.domain.CommunityUserRole;
import com.guildup.community.dto.CommunityUserResponse;
import com.guildup.community.dto.DiscoverableCommunityResponse;
import com.guildup.community.exception.AlreadyCommunityMemberException;
import com.guildup.community.exception.DiscordCommunityConnectionNotFoundException;
import com.guildup.community.repository.CommunityRepository;
import com.guildup.community.repository.CommunityUserRepository;
import com.guildup.community.repository.DiscordCommunityConnectionRepository;
import com.guildup.discord.oauth.dto.CommunityJoinResponse;
import com.guildup.discord.oauth.dto.DiscordGuildSelectionResponse;
import com.guildup.discord.oauth.store.DiscordOAuthSessionStore;
import com.guildup.discord.service.DiscordGuildService;
import com.guildup.discord.service.DiscordMemberService;
import com.guildup.user.repository.UserExternalAccountRepository;
import com.guildup.user.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** GuildUp Community 가입과 내부 역할 관리를 담당한다. */
@Service
public class CommunityMembershipService {
    private static final Duration NEW_COMMUNITY_WINDOW = Duration.ofHours(1);

    private final CommunityAccessService access;
    private final DiscordOAuthSessionStore oauthResults;
    private final DiscordCommunityConnectionRepository connections;
    private final CommunityUserRepository memberships;
    private final UserRepository users;
    private final CommunityRepository communities;
    private final UserExternalAccountRepository externalAccounts;
    private final DiscordGuildService discordGuilds;
    private final DiscordMemberService discordMembers;

    public CommunityMembershipService(
            CommunityAccessService access,
            DiscordOAuthSessionStore oauthResults,
            DiscordCommunityConnectionRepository connections,
            CommunityUserRepository memberships,
            UserRepository users,
            CommunityRepository communities,
            UserExternalAccountRepository externalAccounts,
            DiscordGuildService discordGuilds,
            DiscordMemberService discordMembers
    ) {
        this.access = access;
        this.oauthResults = oauthResults;
        this.connections = connections;
        this.memberships = memberships;
        this.users = users;
        this.communities = communities;
        this.externalAccounts = externalAccounts;
        this.discordGuilds = discordGuilds;
        this.discordMembers = discordMembers;
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

    /** OAuth가 확인한 서버 멤버십을 별도의 GuildUp MEMBER 가입으로 변환한다. */
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
                    connection.getCommunity(), user, CommunityUserRole.MEMBER
            ));
            discardNewUnconnectedSource(sourceMembership, targetCommunityId);
            return CommunityJoinResponse.from(joined);
        } catch (DataIntegrityViolationException exception) {
            throw new AlreadyCommunityMemberException(targetCommunityId);
        }
    }

    /** 현재 Discord 계정이 실제로 속한 서버와 연결된, 아직 가입하지 않은 Community를 찾는다. */
    @Transactional(readOnly = true)
    public List<DiscoverableCommunityResponse> discoverByDiscordMembership(Long userId) {
        String discordUserId = requireDiscordUserId(userId);
        List<DiscoverableCommunityResponse> discovered = new ArrayList<>();
        for (var connection : connections.findAllWithCommunity()) {
            Long communityId = connection.getCommunity().getId();
            if (memberships.existsByCommunityIdAndUserId(communityId, userId)) {
                continue;
            }
            var guild = discordGuilds.findGuildById(connection.getDiscordGuildId());
            try {
                if (guild.isPresent() && discordMembers.containsUser(guild.get(), discordUserId)) {
                    discovered.add(DiscoverableCommunityResponse.from(connection));
                }
            } catch (RuntimeException ignored) {
                // 한 Discord 서버의 일시적 조회 실패가 다른 가입 후보 조회를 막지 않게 한다.
            }
        }
        return List.copyOf(discovered);
    }

    /** Discord 서버 소속을 검증한 뒤 GuildUp Community Membership을 MEMBER로 생성한다. */
    @Transactional
    public CommunityJoinResponse joinDiscoveredCommunity(Long userId, Long communityId) {
        var existing = memberships.findByCommunityIdAndUserId(communityId, userId);
        if (existing.isPresent()) {
            return CommunityJoinResponse.from(existing.get());
        }
        String discordUserId = requireDiscordUserId(userId);
        var connection = connections.findByCommunityId(communityId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Discord-connected Community does not exist"));
        var guild = discordGuilds.findGuildById(connection.getDiscordGuildId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "GuildUp bot is not connected to the Discord server"));
        if (!discordMembers.containsUser(guild, discordUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Discord guild membership is required to join this Community");
        }
        var user = users.findById(userId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login user does not exist"));
        try {
            return CommunityJoinResponse.from(memberships.saveAndFlush(new CommunityUser(
                    connection.getCommunity(), user, CommunityUserRole.MEMBER
            )));
        } catch (DataIntegrityViolationException exception) {
            throw new AlreadyCommunityMemberException(communityId);
        }
    }

    @Transactional(readOnly = true)
    public List<CommunityUserResponse> getCommunityUsers(Long userId, Long communityId) {
        access.requireCommunityAdmin(userId, communityId);
        return memberships.findByCommunityIdOrderByIdAsc(communityId).stream()
                .map(CommunityUserResponse::from)
                .toList();
    }

    /** OWNER는 기존 OWNER를 제외한 Membership을 ADMIN 또는 MEMBER로 변경할 수 있다. */
    @Transactional
    public CommunityUserResponse changeRole(
            Long ownerUserId, Long communityId, Long targetUserId, CommunityUserRole role
    ) {
        access.requireCommunityOwner(ownerUserId, communityId);
        if (role == null || role == CommunityUserRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Role must be ADMIN or MEMBER");
        }
        CommunityUser target = memberships.findByCommunityIdAndUserId(communityId, targetUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Community user does not exist"));
        if (target.getRole() == CommunityUserRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Community OWNER role cannot be changed here");
        }
        target.changeRole(role);
        return CommunityUserResponse.from(target);
    }

    private String requireDiscordUserId(Long userId) {
        return externalAccounts.findByUserIdAndProvider(userId, ExternalAccountProvider.DISCORD)
                .map(account -> account.getExternalUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "A linked Discord account is required"));
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
