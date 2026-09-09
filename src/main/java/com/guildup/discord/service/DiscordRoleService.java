package com.guildup.discord.service;

import com.guildup.discord.dto.DiscordMemberResponse;
import com.guildup.discord.dto.DiscordRoleResponse;
import com.guildup.discord.exception.DiscordResourceNotFoundException;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/** Discord 역할 목록과 역할별 사용자 목록을 API 응답 형태로 가공한다. */
@Service
public class DiscordRoleService {

    private final DiscordGuildService discordGuildService;
    private final DiscordMemberService discordMemberService;

    public DiscordRoleService(
            DiscordGuildService discordGuildService,
            DiscordMemberService discordMemberService
    ) {
        this.discordGuildService = discordGuildService;
        this.discordMemberService = discordMemberService;
    }

    /** @everyone 역할을 제외하고 높은 위치의 역할부터 반환한다. */
    public List<DiscordRoleResponse> getRoles(String guildId) {
        Guild guild = discordGuildService.getGuildById(guildId);

        return guild.getRoles().stream()
                .filter(role -> !role.isPublicRole())
                .sorted(Comparator.comparingInt(Role::getPosition).reversed())
                .map(role -> new DiscordRoleResponse(role.getId(), role.getName()))
                .toList();
    }

    /** 서버의 봇이 아닌 전체 멤버를 표시 이름 순으로 반환한다. */
    public List<DiscordMemberResponse> getMembers(String guildId) {
        Guild guild = discordGuildService.getGuildById(guildId);
        return discordMemberService.getHumanMembers(guild).stream()
                .map(this::toResponse)
                .sorted(Comparator.comparing(
                        DiscordMemberResponse::displayName,
                        String.CASE_INSENSITIVE_ORDER
                ))
                .toList();
    }

    /** 지정한 역할의 일반 사용자를 표시 이름 순으로 정렬해 반환한다. */
    public List<DiscordMemberResponse> getMembers(String guildId, String roleId) {
        Guild guild = discordGuildService.getGuildById(guildId);
        Role role = findRole(guild, roleId);

        return discordMemberService.getMembersWithRole(guild, role).stream()
                .map(this::toResponse)
                .sorted(Comparator.comparing(
                        DiscordMemberResponse::displayName,
                        String.CASE_INSENSITIVE_ORDER
                ))
                .toList();
    }

    /** 역할 ID 형식과 존재 여부를 확인하며 @everyone은 직접 조회하지 못하게 한다. */
    private Role findRole(Guild guild, String roleId) {
        Role role;

        try {
            role = guild.getRoleById(roleId);
        } catch (IllegalArgumentException exception) {
            throw new DiscordResourceNotFoundException("Discord role not found: " + roleId);
        }

        if (role == null || role.isPublicRole()) {
            throw new DiscordResourceNotFoundException("Discord role not found: " + roleId);
        }

        return role;
    }

    /** JDA Member를 화면 공개용 DTO로 변환한다. */
    private DiscordMemberResponse toResponse(Member member) {
        return new DiscordMemberResponse(
                member.getUser().getId(),
                member.getUser().getName(),
                discordMemberService.getDisplayName(member),
                member.getEffectiveAvatarUrl()
        );
    }
}
