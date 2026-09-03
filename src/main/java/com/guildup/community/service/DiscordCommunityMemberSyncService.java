package com.guildup.community.service;

import com.guildup.community.domain.Community;
import com.guildup.community.domain.CommunityMember;
import com.guildup.community.repository.CommunityMemberRepository;
import com.guildup.community.repository.CommunityRepository;
import com.guildup.discord.service.DiscordGuildService;
import com.guildup.discord.service.DiscordMemberService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DiscordCommunityMemberSyncService {

    private final CommunityRepository communityRepository;
    private final CommunityMemberRepository communityMemberRepository;
    private final DiscordGuildService discordGuildService;
    private final DiscordMemberService discordMemberService;

    public DiscordCommunityMemberSyncService(
            CommunityRepository communityRepository,
            CommunityMemberRepository communityMemberRepository,
            DiscordGuildService discordGuildService,
            DiscordMemberService discordMemberService
    ) {
        this.communityRepository = communityRepository;
        this.communityMemberRepository = communityMemberRepository;
        this.discordGuildService = discordGuildService;
        this.discordMemberService = discordMemberService;
    }

    @Transactional
    public SyncResult syncMembersFromDiscord(Long communityId) {
        Community community = communityRepository.findById(communityId)
                .orElseThrow(() -> new IllegalArgumentException("Community not found: " + communityId));

        String discordGuildId = requireConfigured(
                community.getDiscordGuildId(),
                "Discord guild ID is not configured"
        );
        String discordMemberRoleId = requireConfigured(
                community.getDiscordMemberRoleId(),
                "Discord member role ID is not configured"
        );

        Guild guild = discordGuildService.getGuildById(discordGuildId);
        List<Member> discordMembers = discordMemberService.getMembersWithRole(guild, discordMemberRoleId);
        Map<String, CommunityMember> existingMembersByDiscordUserId = getExistingMembersByDiscordUserId(communityId);

        int added = 0;
        int updated = 0;

        for (Member discordMember : discordMembers) {
            String discordUserId = discordMember.getUser().getId();
            String displayName = discordMemberService.getDisplayName(discordMember);
            CommunityMember existingMember = existingMembersByDiscordUserId.remove(discordUserId);

            if (existingMember == null) {
                communityMemberRepository.save(new CommunityMember(community, displayName, discordUserId));
                added++;
            } else if (existingMember.updateNickname(displayName)) {
                updated++;
            }
        }

        int removed = existingMembersByDiscordUserId.size();
        communityMemberRepository.deleteAll(existingMembersByDiscordUserId.values());

        long total = communityMemberRepository.countByCommunityId(communityId);
        return new SyncResult(added, updated, removed, total);
    }

    private Map<String, CommunityMember> getExistingMembersByDiscordUserId(Long communityId) {
        Map<String, CommunityMember> membersByDiscordUserId = new HashMap<>();

        for (CommunityMember member : communityMemberRepository
                .findByCommunityIdAndDiscordUserIdIsNotNull(communityId)) {
            membersByDiscordUserId.put(member.getDiscordUserId(), member);
        }

        return membersByDiscordUserId;
    }

    private String requireConfigured(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(message);
        }

        return value;
    }

    public record SyncResult(
            int added,
            int updated,
            int removed,
            long total
    ) {
    }
}
