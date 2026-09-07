package com.guildup.community.service;

import com.guildup.community.domain.Community;
import com.guildup.community.domain.CommunityMemberRoleSetting;
import com.guildup.community.dto.CommunityMemberRoleSettingsResponse;
import com.guildup.community.repository.CommunityMemberRoleSettingRepository;
import com.guildup.discord.dto.DiscordRoleResponse;
import com.guildup.discord.service.DiscordRoleService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 커뮤니티별 클랜원 판별 Discord 역할 설정을 조회하고 갱신한다. */
@Service
public class CommunityMemberRoleSettingService {

    private final CommunityAccessService accessService;
    private final DiscordCommunityConnectionService connectionService;
    private final DiscordRoleService discordRoleService;
    private final CommunityMemberRoleSettingRepository settingRepository;

    public CommunityMemberRoleSettingService(
            CommunityAccessService accessService,
            DiscordCommunityConnectionService connectionService,
            DiscordRoleService discordRoleService,
            CommunityMemberRoleSettingRepository settingRepository
    ) {
        this.accessService = accessService;
        this.connectionService = connectionService;
        this.discordRoleService = discordRoleService;
        this.settingRepository = settingRepository;
    }

    @Transactional(readOnly = true)
    public CommunityMemberRoleSettingsResponse getSettings(Long userId, Long communityId) {
        accessService.requireAccess(userId, communityId);
        connectionService.getRequiredConnection(communityId);
        return CommunityMemberRoleSettingsResponse.from(
                settingRepository.findByCommunityIdOrderByIdAsc(communityId)
        );
    }

    /** 선택 가능한 역할을 한 번 조회해 모든 ID를 검증한 뒤 기존 설정을 원자적으로 교체한다. */
    @Transactional
    public CommunityMemberRoleSettingsResponse updateSettings(
            Long userId,
            Long communityId,
            List<String> discordRoleIds
    ) {
        Community community = accessService.requireManagementAccess(userId, communityId).getCommunity();
        var connection = connectionService.getRequiredConnection(communityId);
        List<String> normalizedRoleIds = normalizeRoleIds(discordRoleIds);
        if (normalizedRoleIds.isEmpty()) {
            settingRepository.deleteByCommunityId(communityId);
            return new CommunityMemberRoleSettingsResponse(List.of());
        }
        Map<String, DiscordRoleResponse> availableRoles = discordRoleService
                .getRoles(connection.getDiscordGuildId()).stream()
                .collect(Collectors.toMap(DiscordRoleResponse::id, Function.identity()));

        List<CommunityMemberRoleSetting> replacements = normalizedRoleIds.stream()
                .map(roleId -> toSetting(community, roleId, availableRoles))
                .toList();

        settingRepository.deleteByCommunityId(communityId);
        List<CommunityMemberRoleSetting> saved = settingRepository.saveAll(replacements);
        return CommunityMemberRoleSettingsResponse.from(saved);
    }

    private List<String> normalizeRoleIds(List<String> discordRoleIds) {
        if (discordRoleIds == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "discordRoleIds must be an array");
        }

        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String roleId : discordRoleIds) {
            if (roleId == null || roleId.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Discord role ID must not be blank");
            }
            normalized.add(roleId.trim());
        }
        return List.copyOf(normalized);
    }

    private CommunityMemberRoleSetting toSetting(
            Community community,
            String roleId,
            Map<String, DiscordRoleResponse> availableRoles
    ) {
        DiscordRoleResponse role = availableRoles.get(roleId);
        if (role == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Discord role does not exist in the connected server: " + roleId);
        }
        return new CommunityMemberRoleSetting(community, role.id(), role.name());
    }
}
