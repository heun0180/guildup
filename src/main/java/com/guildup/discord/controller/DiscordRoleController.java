package com.guildup.discord.controller;

import com.guildup.discord.dto.DiscordMemberResponse;
import com.guildup.discord.dto.DiscordRoleResponse;
import com.guildup.discord.service.DiscordRoleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Discord 서버 ID를 직접 받아 역할 및 역할별 멤버를 조회하는 API다. */
@RestController
@RequestMapping("/api/discord/guilds/{guildId}/roles")
public class DiscordRoleController {

    private final DiscordRoleService discordRoleService;

    public DiscordRoleController(DiscordRoleService discordRoleService) {
        this.discordRoleService = discordRoleService;
    }

    /** @everyone을 제외한 서버 역할 목록을 반환한다. */
    @GetMapping
    public List<DiscordRoleResponse> getRoles(@PathVariable String guildId) {
        return discordRoleService.getRoles(guildId);
    }

    /** 특정 Discord 역할을 가진 일반 사용자 목록을 반환한다. */
    @GetMapping("/{roleId}/members")
    public List<DiscordMemberResponse> getMembers(
            @PathVariable String guildId,
            @PathVariable String roleId
    ) {
        return discordRoleService.getMembers(guildId, roleId);
    }
}
