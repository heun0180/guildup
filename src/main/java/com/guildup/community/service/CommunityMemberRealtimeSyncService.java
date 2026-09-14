package com.guildup.community.service;

import com.guildup.community.repository.CommunityMemberRoleSettingRepository;
import com.guildup.community.repository.DiscordCommunityConnectionRepository;
import net.dv8tion.jda.api.entities.Member;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Set;
import java.util.stream.Collectors;

/** Discord Gateway가 전달한 한 명의 현재 상태를 GuildUp DB에 반영한다. */
@Service
public class CommunityMemberRealtimeSyncService {

    private final DiscordCommunityConnectionRepository connectionRepository;
    private final CommunityMemberRoleSettingRepository roleSettingRepository;
    private final CommunityMemberDiscordStateService stateService;
    private final CommunityMemberSyncLockManager lockManager;
    private final Clock clock;

    public CommunityMemberRealtimeSyncService(
            DiscordCommunityConnectionRepository connectionRepository,
            CommunityMemberRoleSettingRepository roleSettingRepository,
            CommunityMemberDiscordStateService stateService,
            CommunityMemberSyncLockManager lockManager,
            Clock clock
    ) {
        this.connectionRepository = connectionRepository;
        this.roleSettingRepository = roleSettingRepository;
        this.stateService = stateService;
        this.lockManager = lockManager;
        this.clock = clock;
    }

    public void synchronize(Member member) {
        if (member.getUser().isBot()) return;
        connectionRepository.findByDiscordGuildId(member.getGuild().getId()).ifPresent(connection -> {
            Long communityId = connection.getCommunity().getId();
            String discordUserId = member.getUser().getId();
            lockManager.withMemberUpdate(communityId, discordUserId, () -> {
                Set<String> roleIds = roleSettingRepository.findByCommunityIdOrderByIdAsc(communityId).stream()
                        .map(setting -> setting.getDiscordRoleId())
                        .collect(Collectors.toUnmodifiableSet());
                stateService.synchronizeMember(communityId, member, roleIds, clock.instant());
            });
        });
    }

    public void memberRemoved(String discordGuildId, String discordUserId, boolean bot) {
        if (bot) return;
        connectionRepository.findByDiscordGuildId(discordGuildId).ifPresent(connection -> {
            Long communityId = connection.getCommunity().getId();
            lockManager.withMemberUpdate(communityId, discordUserId,
                    () -> stateService.markMemberLeft(communityId, discordUserId, clock.instant()));
        });
    }
}
