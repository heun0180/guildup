package com.guildup.community.service;

import com.guildup.community.dto.CommunityMemberSyncResponse;
import com.guildup.community.repository.CommunityMemberRoleSettingRepository;
import com.guildup.discord.service.DiscordGuildService;
import com.guildup.discord.service.DiscordMemberService;
import net.dv8tion.jda.api.entities.Member;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 요청 또는 복구 큐에서 Community 단위 Discord 전체 reconciliation을 실행한다. */
@Service
public class CommunityMemberSyncService {

    private final CommunityAccessService accessService;
    private final DiscordCommunityConnectionService connectionService;
    private final CommunityMemberRoleSettingRepository roleSettingRepository;
    private final DiscordGuildService discordGuildService;
    private final DiscordMemberService discordMemberService;
    private final CommunityMemberDiscordStateService stateService;
    private final CommunityMemberSyncLockManager lockManager;
    private final Clock clock;

    public CommunityMemberSyncService(
            CommunityAccessService accessService,
            DiscordCommunityConnectionService connectionService,
            CommunityMemberRoleSettingRepository roleSettingRepository,
            DiscordGuildService discordGuildService,
            DiscordMemberService discordMemberService,
            CommunityMemberDiscordStateService stateService,
            CommunityMemberSyncLockManager lockManager,
            Clock clock
    ) {
        this.accessService = accessService;
        this.connectionService = connectionService;
        this.roleSettingRepository = roleSettingRepository;
        this.discordGuildService = discordGuildService;
        this.discordMemberService = discordMemberService;
        this.stateService = stateService;
        this.lockManager = lockManager;
        this.clock = clock;
    }

    /** 관리 권한을 확인한 뒤 복구용 전체 동기화를 수행한다. */
    public CommunityMemberSyncResponse synchronize(Long userId, Long communityId) {
        accessService.requireManagementAccess(userId, communityId);
        return reconcile(communityId);
    }

    /** 시작 복구 큐 등 내부 작업에서 사용자 세션 없이 동일한 전체 동기화를 수행한다. */
    public CommunityMemberSyncResponse reconcile(Long communityId) {
        return lockManager.withReconciliation(communityId, () -> reconcileLocked(communityId));
    }

    private CommunityMemberSyncResponse reconcileLocked(Long communityId) {
        var connection = connectionService.getRequiredConnection(communityId);
        Set<String> configuredRoleIds = roleSettingRepository
                .findByCommunityIdOrderByIdAsc(communityId).stream()
                .map(setting -> setting.getDiscordRoleId())
                .collect(Collectors.toUnmodifiableSet());

        // 역할을 모두 제거하면 Discord 조회 없이 Discord 연결 멤버만 LEFT 처리한다.
        List<Member> discordMembers = List.of();
        if (!configuredRoleIds.isEmpty()) {
            var guild = discordGuildService.getGuildById(connection.getDiscordGuildId());
            discordMembers = discordMemberService.loadMembersForReconciliation(guild);
        }
        return stateService.reconcileSnapshot(
                communityId, discordMembers, configuredRoleIds, clock.instant()
        );
    }
}
