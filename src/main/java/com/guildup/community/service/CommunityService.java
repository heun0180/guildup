package com.guildup.community.service;

import com.guildup.community.domain.Community;
import com.guildup.community.repository.CommunityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommunityService {

    private final CommunityRepository communityRepository;

    public CommunityService(CommunityRepository communityRepository) {
        this.communityRepository = communityRepository;
    }

    @Transactional
    public Community createCommunity(String name, String discordGuildId) {
        if (communityRepository.findByDiscordGuildId(discordGuildId).isPresent()) {
            throw new IllegalStateException("Community already exists for Discord guild: " + discordGuildId);
        }

        Community community = new Community(name, discordGuildId);
        return communityRepository.save(community);
    }

    @Transactional
    public Community configureDiscordMemberRole(Long communityId, String roleId) {
        Community community = communityRepository.findById(communityId)
                .orElseThrow(() -> new IllegalArgumentException("Community not found: " + communityId));

        if (roleId == null || roleId.isBlank()) {
            throw new IllegalArgumentException("Discord member role ID must not be blank");
        }

        community.configureDiscordMemberRole(roleId.trim());
        return community;
    }
}
