package com.guildup.community.service;

import com.guildup.community.domain.DiscordCommunityConnection;
import com.guildup.discord.dto.DiscordMemberResponse;
import com.guildup.discord.dto.DiscordRoleResponse;
import com.guildup.discord.service.DiscordRoleService;
import org.springframework.stereotype.Service;

import java.util.List;

/** 커뮤니티 ID를 저장된 Discord 서버 ID로 변환해 역할/멤버 조회 서비스에 연결한다. */
@Service
public class CommunityDiscordQueryService {

    private final DiscordCommunityConnectionService connectionService;
    private final DiscordRoleService discordRoleService;

    public CommunityDiscordQueryService(
            DiscordCommunityConnectionService connectionService,
            DiscordRoleService discordRoleService
    ) {
        this.connectionService = connectionService;
        this.discordRoleService = discordRoleService;
    }

    /** 커뮤니티의 Discord 연결을 찾은 뒤 해당 서버의 역할을 조회한다. */
    public List<DiscordRoleResponse> getRoles(Long communityId) {
        DiscordCommunityConnection connection = connectionService.getRequiredConnection(communityId);
        return discordRoleService.getRoles(connection.getDiscordGuildId());
    }

    /** 커뮤니티의 Discord 연결을 찾은 뒤 특정 역할을 가진 멤버를 조회한다. */
    public List<DiscordMemberResponse> getMembers(Long communityId, String roleId) {
        DiscordCommunityConnection connection = connectionService.getRequiredConnection(communityId);
        return discordRoleService.getMembers(connection.getDiscordGuildId(), roleId);
    }
}
