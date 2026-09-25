package com.guildup.killcompetition.service;

import com.guildup.community.domain.CommunityMember;
import com.guildup.community.domain.CommunityUser;
import com.guildup.community.domain.CommunityUserRole;
import com.guildup.community.service.CommunityAccessService;
import com.guildup.community.service.CurrentCommunityMemberService;
import com.guildup.killcompetition.domain.KillCompetition;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;

/** Kill Competition 관리 권한의 단일 진실 원천이다. */
@Service
public class KillCompetitionManagementAccess {
    private final CommunityAccessService communities;
    private final CurrentCommunityMemberService currentMembers;

    public KillCompetitionManagementAccess(CommunityAccessService communities,
                                           CurrentCommunityMemberService currentMembers) {
        this.communities = communities;
        this.currentMembers = currentMembers;
    }

    public boolean canManage(Long userId, Long communityId, KillCompetition competition) {
        CommunityUser membership = communities.requireCommunityMember(userId, communityId);
        if (isCommunityAdministrator(membership)) return true;
        CommunityMember member = currentMembers.find(userId, communityId).orElse(null);
        return member != null && Objects.equals(member.getId(), competition.getCreatedBy().getId());
    }

    public void requireCanManage(Long userId, Long communityId, KillCompetition competition) {
        if (!canManage(userId, communityId, competition)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "킬내기 생성자 또는 커뮤니티 관리자만 수행할 수 있습니다.");
        }
    }

    public boolean isCommunityAdministrator(CommunityUser membership) {
        return membership.getRole() == CommunityUserRole.OWNER
                || membership.getRole() == CommunityUserRole.ADMIN;
    }
}
