package com.guildup.discord.bot;

import com.guildup.community.service.CommunityMemberRealtimeSyncService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleAddEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleRemoveEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberUpdateEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiscordMemberEventListenerTests {

    private final CommunityMemberRealtimeSyncService service = mock(CommunityMemberRealtimeSyncService.class);
    private final DiscordMemberEventListener listener = new DiscordMemberEventListener(
            service, new DiscordMemberEventDispatcher(Runnable::run)
    );
    private final JDA jda = mock(JDA.class);

    @Test
    void genericAndSpecificRoleEventsDelegateCurrentMember() {
        Member member = member();

        listener.onGuildMemberUpdate(new GuildMemberUpdateEvent(jda, 1, member));
        listener.onGuildMemberRoleAdd(new GuildMemberRoleAddEvent(jda, 2, member, List.of()));
        listener.onGuildMemberRoleRemove(new GuildMemberRoleRemoveEvent(jda, 3, member, List.of()));

        verify(service, org.mockito.Mockito.times(3)).synchronize(member);
    }

    @Test
    void guildRemoveUsesUserIdEvenWhenMemberWasNotCached() {
        Guild guild = mock(Guild.class);
        when(guild.getId()).thenReturn("100");
        User user = mock(User.class);
        when(user.getId()).thenReturn("300");
        when(user.isBot()).thenReturn(false);

        listener.onGuildMemberRemove(new GuildMemberRemoveEvent(jda, 1, guild, user, null));

        verify(service).memberRemoved("100", "300", false);
    }

    @Test
    void serviceFailureDoesNotEscapeJdaEventCallback() {
        Member member = member();
        doThrow(new IllegalStateException("database unavailable")).when(service).synchronize(member);

        assertThatCode(() -> listener.onGuildMemberUpdate(new GuildMemberUpdateEvent(jda, 1, member)))
                .doesNotThrowAnyException();
    }

    private Member member() {
        Guild guild = mock(Guild.class);
        when(guild.getId()).thenReturn("100");
        User user = mock(User.class);
        when(user.getId()).thenReturn("300");
        Member member = mock(Member.class);
        when(member.getGuild()).thenReturn(guild);
        when(member.getUser()).thenReturn(user);
        return member;
    }
}
