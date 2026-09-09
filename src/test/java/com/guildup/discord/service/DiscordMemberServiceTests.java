package com.guildup.discord.service;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DiscordMemberServiceTests {

    private final DiscordMemberService discordMemberService = new DiscordMemberService();

    @Test
    void getMembersWithRoleExcludesBots() {
        Guild guild = mock(Guild.class);
        Role role = mock(Role.class);
        Member userMember = member(false);
        Member botMember = member(true);
        when(guild.getMembersWithRoles(role)).thenReturn(List.of(userMember, botMember));

        List<Member> members = discordMemberService.getMembersWithRole(guild, role);

        assertThat(members).containsExactly(userMember);
    }

    @Test
    void getHumanMembersExcludesBots() {
        Guild guild = mock(Guild.class);
        Member userMember = member(false);
        Member botMember = member(true);
        when(guild.getMembers()).thenReturn(List.of(userMember, botMember));

        assertThat(discordMemberService.getHumanMembers(guild)).containsExactly(userMember);
    }

    @Test
    void findHumanMemberRejectsBotAndInvalidId() {
        Guild guild = mock(Guild.class);
        Member botMember = member(true);
        when(guild.getMemberById("100")).thenReturn(botMember);
        when(guild.getMemberById("invalid")).thenThrow(new IllegalArgumentException("invalid id"));

        assertThat(discordMemberService.findHumanMember(guild, "100")).isEmpty();
        assertThat(discordMemberService.findHumanMember(guild, "invalid")).isEmpty();
    }

    @Test
    void displayNamePrefersGuildNickname() {
        Member member = displayNameMember("서버 별명", "전역 이름", "username");

        assertThat(discordMemberService.getDisplayName(member)).isEqualTo("서버 별명");
    }

    @Test
    void displayNameUsesGlobalNameWhenGuildNicknameIsMissing() {
        Member member = displayNameMember(null, "전역 이름", "username");

        assertThat(discordMemberService.getDisplayName(member)).isEqualTo("전역 이름");
    }

    @Test
    void displayNameUsesUsernameWhenOtherNamesAreMissing() {
        Member member = displayNameMember(null, null, "username");

        assertThat(discordMemberService.getDisplayName(member)).isEqualTo("username");
    }

    private Member member(boolean bot) {
        Member member = mock(Member.class);
        User user = mock(User.class);
        when(member.getUser()).thenReturn(user);
        when(user.isBot()).thenReturn(bot);
        return member;
    }

    private Member displayNameMember(String nickname, String globalName, String username) {
        Member member = mock(Member.class);
        User user = mock(User.class);
        when(member.getNickname()).thenReturn(nickname);
        when(member.getUser()).thenReturn(user);
        when(user.getGlobalName()).thenReturn(globalName);
        when(user.getName()).thenReturn(username);
        return member;
    }
}
