package com.guildup.discord.service;

import com.guildup.discord.dto.DiscordMemberResponse;
import com.guildup.discord.dto.DiscordRoleResponse;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DiscordRoleServiceTests {

    private final DiscordGuildService discordGuildService = mock(DiscordGuildService.class);
    private final DiscordMemberService discordMemberService = mock(DiscordMemberService.class);
    private final DiscordRoleService discordRoleService =
            new DiscordRoleService(discordGuildService, discordMemberService);

    @Test
    void getRolesExcludesEveryoneAndOrdersRolesByPosition() {
        Guild guild = mock(Guild.class);
        Role everyone = role("1", "@everyone", 0, true);
        Role member = role("2", "클랜원", 1, false);
        Role manager = role("3", "운영진", 2, false);
        when(discordGuildService.getGuildById("100")).thenReturn(guild);
        when(guild.getRoles()).thenReturn(List.of(everyone, member, manager));

        List<DiscordRoleResponse> roles = discordRoleService.getRoles("100");

        assertThat(roles).containsExactly(
                new DiscordRoleResponse("3", "운영진"),
                new DiscordRoleResponse("2", "클랜원")
        );
    }

    @Test
    void getMembersReturnsDtoInsteadOfJdaMember() {
        Guild guild = mock(Guild.class);
        Role role = role("2", "클랜원", 1, false);
        Member member = mock(Member.class);
        User user = mock(User.class);
        when(discordGuildService.getGuildById("100")).thenReturn(guild);
        when(guild.getRoleById("2")).thenReturn(role);
        when(discordMemberService.getMembersWithRole(guild, role)).thenReturn(List.of(member));
        when(member.getUser()).thenReturn(user);
        when(user.getId()).thenReturn("200");
        when(user.getName()).thenReturn("apple_account");
        when(discordMemberService.getDisplayName(member)).thenReturn("애플");
        when(member.getEffectiveAvatarUrl()).thenReturn("https://cdn.example/avatar.png");

        List<DiscordMemberResponse> members = discordRoleService.getMembers("100", "2");

        assertThat(members).containsExactly(new DiscordMemberResponse(
                "200",
                "apple_account",
                "애플",
                "https://cdn.example/avatar.png"
        ));
    }

    private Role role(String id, String name, int position, boolean publicRole) {
        Role role = mock(Role.class);
        when(role.getId()).thenReturn(id);
        when(role.getName()).thenReturn(name);
        when(role.getPosition()).thenReturn(position);
        when(role.isPublicRole()).thenReturn(publicRole);
        return role;
    }
}
