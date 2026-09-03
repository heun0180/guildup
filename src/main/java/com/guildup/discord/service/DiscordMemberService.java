package com.guildup.discord.service;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DiscordMemberService {

    private static final Logger log = LoggerFactory.getLogger(DiscordMemberService.class);

    public List<Member> getMembers(Guild guild) {
        return guild.getMembers();
    }

    public List<Member> getMembersWithRole(Guild guild, String roleId) {
        Role role = guild.getRoleById(roleId);
        if (role == null) {
            throw new IllegalStateException("Discord role not found: " + roleId);
        }

        return guild.getMembersWithRoles(role).stream()
                .filter(member -> !member.getUser().isBot())
                .toList();
    }

    public String getDisplayName(Member member) {
        if (member.getNickname() != null) {
            return member.getNickname();
        }

        if (member.getUser().getGlobalName() != null) {
            return member.getUser().getGlobalName();
        }

        return member.getUser().getName();
    }

    public void logMembers(Guild guild) {
        List<Member> members = getMembers(guild);
        log.info("Discord guild member count - guild: {}, count: {}", guild.getName(), members.size());

        for (Member member : members) {
            log.info(
                    "Discord member - userId: {}, username: {}, displayName: {}, bot: {}",
                    member.getUser().getId(),
                    member.getUser().getName(),
                    member.getEffectiveName(),
                    member.getUser().isBot()
            );
        }
    }
}
