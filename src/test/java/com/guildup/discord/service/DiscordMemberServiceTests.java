package com.guildup.discord.service;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.utils.concurrent.Task;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiscordMemberServiceTests {

    private final DiscordMemberService discordMemberService = new DiscordMemberService();

    @Test
    void getMembersWithRoleExcludesBots() {
        Guild guild = mock(Guild.class);
        Role role = mock(Role.class);
        Member userMember = member(false, role);
        Member botMember = member(true, role);
        loadMembers(guild, List.of(userMember, botMember));

        List<Member> members = discordMemberService.getMembersWithRole(guild, role);

        assertThat(members).containsExactly(userMember);
    }

    @Test
    void getHumanMembersExcludesBotsAndReusesRecentGuildSnapshot() {
        Guild guild = mock(Guild.class);
        Member userMember = member(false);
        Member botMember = member(true);
        loadMembers(guild, List.of(userMember, botMember));

        assertThat(discordMemberService.getHumanMembers(guild)).containsExactly(userMember);
        assertThat(discordMemberService.getHumanMembers(guild)).containsExactly(userMember);
        verify(guild, times(1)).loadMembers();
    }

    @Test
    @SuppressWarnings("unchecked")
    void concurrentRequestsForSameGuildShareOneMemberLoad() throws Exception {
        Guild guild = mock(Guild.class);
        Member member = member(false);
        Task<List<Member>> task = mock(Task.class);
        CountDownLatch loadStarted = new CountDownLatch(1);
        CountDownLatch releaseLoad = new CountDownLatch(1);
        when(guild.loadMembers()).thenReturn(task);
        when(task.get()).thenAnswer(ignored -> {
            loadStarted.countDown();
            releaseLoad.await(1, TimeUnit.SECONDS);
            return List.of(member);
        });
        var executor = Executors.newFixedThreadPool(2);

        try {
            var first = executor.submit(() -> discordMemberService.getMembers(guild));
            assertThat(loadStarted.await(1, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> discordMemberService.getMembers(guild));
            releaseLoad.countDown();

            assertThat(first.get(1, TimeUnit.SECONDS)).containsExactly(member);
            assertThat(second.get(1, TimeUnit.SECONDS)).containsExactly(member);
            verify(guild, times(1)).loadMembers();
        } finally {
            releaseLoad.countDown();
            executor.shutdownNow();
        }
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

    private Member member(boolean bot, Role... roles) {
        Member member = mock(Member.class);
        User user = mock(User.class);
        when(member.getUser()).thenReturn(user);
        when(user.isBot()).thenReturn(bot);
        when(member.getRoles()).thenReturn(List.of(roles));
        return member;
    }

    @SuppressWarnings("unchecked")
    private void loadMembers(Guild guild, List<Member> members) {
        Task<List<Member>> task = mock(Task.class);
        when(guild.loadMembers()).thenReturn(task);
        when(task.get()).thenReturn(members);
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
