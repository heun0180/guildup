package com.guildup.community.service;

import com.guildup.community.domain.CommunityUser;
import com.guildup.community.domain.CommunityUserRole;
import com.guildup.community.repository.CommunityUserRepository;
import com.guildup.community.repository.DiscordCommunityConnectionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class CommunityAccessService {
    private final CommunityUserRepository memberships;
    private final DiscordCommunityConnectionRepository connections;

    public CommunityAccessService(CommunityUserRepository memberships,
                                  DiscordCommunityConnectionRepository connections) {
        this.memberships = memberships;
        this.connections = connections;
    }

    public CommunityUser requireCommunityMember(Long userId, Long communityId) {
        return memberships.findByCommunityIdAndUserId(communityId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Community access denied"));
    }

    /** OWNER 또는 ADMIN만 수행할 수 있는 커뮤니티 관리 작업을 검증한다. */
    public CommunityUser requireCommunityAdmin(Long userId, Long communityId) {
        CommunityUser membership = requireCommunityMember(userId, communityId);
        if (membership.getRole() != CommunityUserRole.OWNER
                && membership.getRole() != CommunityUserRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Community management access denied");
        }
        return membership;
    }

    public CommunityUser requireCommunityOwner(Long userId, Long communityId) {
        CommunityUser membership = requireCommunityMember(userId, communityId);
        if (membership.getRole() != CommunityUserRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Community owner access denied");
        }
        return membership;
    }

    /** 기존 호출부와의 호환성을 유지한다. */
    public CommunityUser requireAccess(Long userId, Long communityId) {
        return requireCommunityMember(userId, communityId);
    }

    /** 기존 호출부와의 호환성을 유지한다. */
    public CommunityUser requireManagementAccess(Long userId, Long communityId) {
        return requireCommunityAdmin(userId, communityId);
    }

    public void requireGuildAccess(Long userId, String guildId) {
        var connection = connections.findByDiscordGuildId(guildId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Community access denied"));
        requireCommunityMember(userId, connection.getCommunity().getId());
    }

    public void requireGuildManagementAccess(Long userId, String guildId) {
        var connection = connections.findByDiscordGuildId(guildId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Community management access denied"));
        requireCommunityAdmin(userId, connection.getCommunity().getId());
    }
}
