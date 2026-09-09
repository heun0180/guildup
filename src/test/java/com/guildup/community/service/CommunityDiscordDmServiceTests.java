package com.guildup.community.service;

import com.guildup.community.domain.Community;
import com.guildup.community.domain.DiscordCommunityConnection;
import com.guildup.community.exception.CommunityNotFoundException;
import com.guildup.community.exception.DiscordCommunityConnectionNotFoundException;
import com.guildup.discord.dto.DiscordDmFailureReason;
import com.guildup.discord.dto.DiscordDmRequest;
import com.guildup.discord.dto.DiscordDmResponse;
import com.guildup.discord.dto.DiscordMemberResponse;
import com.guildup.discord.exception.DuplicateDiscordDmRequestException;
import com.guildup.discord.service.DiscordDirectMessageClient;
import com.guildup.discord.service.DiscordGuildService;
import com.guildup.discord.service.DiscordMemberService;
import com.guildup.discord.service.DiscordRoleService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommunityDiscordDmServiceTests {

    private final CommunityAccessService accessService = mock(CommunityAccessService.class);
    private final DiscordCommunityConnectionService connectionService =
            mock(DiscordCommunityConnectionService.class);
    private final DiscordGuildService guildService = mock(DiscordGuildService.class);
    private final DiscordMemberService memberService = mock(DiscordMemberService.class);
    private final DiscordRoleService roleService = mock(DiscordRoleService.class);
    private final DiscordDirectMessageClient directMessageClient = mock(DiscordDirectMessageClient.class);
    private final Guild guild = mock(Guild.class);
    private final CommunityDiscordDmService service = new CommunityDiscordDmService(
            accessService, connectionService, guildService, memberService, roleService,
            directMessageClient, 2, Duration.ofSeconds(10),
            Clock.fixed(Instant.parse("2026-09-10T00:00:00Z"), ZoneOffset.UTC)
    );

    @Test
    void sendsDmToGuildMember() {
        connectedGuild();
        Member apple = member("애플");
        when(memberService.findHumanMember(guild, "100")).thenReturn(Optional.of(apple));

        DiscordDmResponse response = service.send(7L, 1L,
                new DiscordDmRequest(List.of("100"), "공지입니다."));

        assertThat(response.total()).isEqualTo(1);
        assertThat(response.success()).isEqualTo(1);
        assertThat(response.failed()).isZero();
        assertThat(response.results().getFirst().displayName()).isEqualTo("애플");
        verify(directMessageClient).send("100", "공지입니다.");
    }

    @Test
    void returnsHumanRecipientsWithConfiguredLimitForManagers() {
        connectedGuild();
        List<DiscordMemberResponse> members = List.of(new DiscordMemberResponse(
                "100", "apple_account", "애플", "https://cdn.example/avatar.png"));
        when(roleService.getMembers("guild-1")).thenReturn(members);

        var response = service.getRecipients(7L, 1L);

        assertThat(response.maxRecipients()).isEqualTo(2);
        assertThat(response.members()).isSameAs(members);
        verify(accessService).requireManagementAccess(7L, 1L);
    }

    @Test
    void sendsMultipleDmsSequentiallyThroughClient() {
        connectedGuild();
        Member apple = member("애플");
        Member fox = member("여우");
        when(memberService.findHumanMember(guild, "100")).thenReturn(Optional.of(apple));
        when(memberService.findHumanMember(guild, "200")).thenReturn(Optional.of(fox));

        DiscordDmResponse response = service.send(7L, 1L,
                new DiscordDmRequest(List.of("100", "200"), "모임 안내"));

        assertThat(response.success()).isEqualTo(2);
        var ordered = org.mockito.Mockito.inOrder(directMessageClient);
        ordered.verify(directMessageClient).send("100", "모임 안내");
        ordered.verify(directMessageClient).send("200", "모임 안내");
    }

    @Test
    void keepsSendingAndCollectsPartialFailure() {
        connectedGuild();
        Member apple = member("애플");
        Member fox = member("여우");
        when(memberService.findHumanMember(guild, "100")).thenReturn(Optional.of(apple));
        when(memberService.findHumanMember(guild, "200")).thenReturn(Optional.of(fox));
        RuntimeException failure = new RuntimeException("Discord internal detail");
        doThrow(failure).when(directMessageClient).send("100", "공지");
        when(directMessageClient.isDmNotAvailable(failure)).thenReturn(true);

        DiscordDmResponse response = service.send(7L, 1L,
                new DiscordDmRequest(List.of("100", "200"), "공지"));

        assertThat(response.success()).isEqualTo(1);
        assertThat(response.failed()).isEqualTo(1);
        assertThat(response.results().getFirst().reason())
                .isEqualTo(DiscordDmFailureReason.DM_NOT_AVAILABLE);
        verify(directMessageClient).send("200", "공지");
    }

    @Test
    void rejectsCommunityWithoutDiscordConnection() {
        when(connectionService.getRequiredConnection(1L))
                .thenThrow(new DiscordCommunityConnectionNotFoundException(1L));

        assertThatThrownBy(() -> service.send(7L, 1L, request("100")))
                .isInstanceOf(DiscordCommunityConnectionNotFoundException.class);
    }

    @Test
    void rejectsMissingCommunity() {
        when(connectionService.getRequiredConnection(99L)).thenThrow(new CommunityNotFoundException(99L));

        assertThatThrownBy(() -> service.send(7L, 99L, request("100")))
                .isInstanceOf(CommunityNotFoundException.class);
    }

    @Test
    void doesNotSendToUserOutsideConnectedGuild() {
        connectedGuild();
        when(memberService.findHumanMember(guild, "999")).thenReturn(Optional.empty());

        DiscordDmResponse response = service.send(7L, 1L, request("999"));

        assertThat(response.failed()).isEqualTo(1);
        assertThat(response.results().getFirst().reason())
                .isEqualTo(DiscordDmFailureReason.USER_NOT_FOUND);
        verify(directMessageClient, never()).send("999", "공지");
    }

    @Test
    void rejectsBlankMessage() {
        assertBadRequest(new DiscordDmRequest(List.of("100"), "  "));
    }

    @Test
    void rejectsEmptyRecipients() {
        assertBadRequest(new DiscordDmRequest(List.of(), "공지"));
    }

    @Test
    void removesDuplicateDiscordUserIds() {
        connectedGuild();
        Member apple = member("애플");
        when(memberService.findHumanMember(guild, "100")).thenReturn(Optional.of(apple));

        DiscordDmResponse response = service.send(7L, 1L,
                new DiscordDmRequest(List.of("100", "100"), "공지"));

        assertThat(response.total()).isEqualTo(1);
        verify(directMessageClient).send("100", "공지");
    }

    @Test
    void rejectsRecipientLimitExceeded() {
        assertBadRequest(new DiscordDmRequest(List.of("100", "200", "300"), "공지"));
    }

    @Test
    void rejectsMessageLongerThanDiscordLimit() {
        assertBadRequest(new DiscordDmRequest(List.of("100"), "a".repeat(2_001)));
    }

    @Test
    void rejectsUserWithoutManagementPermission() {
        ResponseStatusException forbidden = new ResponseStatusException(HttpStatus.FORBIDDEN);
        when(accessService.requireManagementAccess(7L, 1L)).thenThrow(forbidden);

        assertThatThrownBy(() -> service.send(7L, 1L, request("100")))
                .isSameAs(forbidden);
        verify(connectionService, never()).getRequiredConnection(1L);
    }

    @Test
    void rejectsSameRequestWithinDuplicateWindow() {
        connectedGuild();
        Member apple = member("애플");
        when(memberService.findHumanMember(guild, "100")).thenReturn(Optional.of(apple));
        DiscordDmRequest request = request("100");
        service.send(7L, 1L, request);

        assertThatThrownBy(() -> service.send(7L, 1L, request))
                .isInstanceOf(DuplicateDiscordDmRequestException.class);
    }

    private void assertBadRequest(DiscordDmRequest request) {
        assertThatThrownBy(() -> service.send(7L, 1L, request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private DiscordDmRequest request(String userId) {
        return new DiscordDmRequest(List.of(userId), "공지");
    }

    private void connectedGuild() {
        DiscordCommunityConnection connection = new DiscordCommunityConnection(
                new Community("GuildUp"), "guild-1", "GuildUp Discord");
        when(connectionService.getRequiredConnection(1L)).thenReturn(connection);
        when(guildService.getGuildById("guild-1")).thenReturn(guild);
    }

    private Member member(String displayName) {
        Member member = mock(Member.class);
        User user = mock(User.class);
        when(member.getUser()).thenReturn(user);
        when(memberService.getDisplayName(member)).thenReturn(displayName);
        return member;
    }
}
