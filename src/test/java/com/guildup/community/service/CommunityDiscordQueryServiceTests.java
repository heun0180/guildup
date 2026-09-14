package com.guildup.community.service;

import com.guildup.community.domain.Community;
import com.guildup.community.domain.DiscordCommunityConnection;
import com.guildup.community.repository.CommunityMemberAccountRepository;
import com.guildup.community.repository.CommunityMemberRepository;
import com.guildup.discord.dto.DiscordMemberResponse;
import com.guildup.discord.dto.DiscordRoleResponse;
import com.guildup.discord.service.DiscordRoleService;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommunityDiscordQueryServiceTests {

    private final DiscordCommunityConnectionService connectionService =
            mock(DiscordCommunityConnectionService.class);
    private final DiscordRoleService discordRoleService = mock(DiscordRoleService.class);
    private final CommunityAccessService accessService = mock(CommunityAccessService.class);
    private final CommunityDiscordQueryService queryService =
            new CommunityDiscordQueryService(connectionService, discordRoleService, accessService);

    @Test
    void usesStoredGuildIdToRequestDiscordRoles() {
        DiscordCommunityConnection connection =
                new DiscordCommunityConnection(new Community("GuildUp 클랜"), "100", "GuildUp Discord");
        List<DiscordRoleResponse> expected = List.of(new DiscordRoleResponse("200", "클랜원"));
        when(connectionService.getRequiredConnection(1L)).thenReturn(connection);
        when(discordRoleService.getRoles("100")).thenReturn(expected);

        List<DiscordRoleResponse> result = queryService.getRoles(10L, 1L);

        assertThat(result).isSameAs(expected);
        verify(accessService).requireCommunityAdmin(10L, 1L);
        verify(discordRoleService).getRoles("100");
    }

    @Test
    void usesStoredGuildIdToRequestDiscordMembersDirectly() {
        DiscordCommunityConnection connection =
                new DiscordCommunityConnection(new Community("GuildUp 클랜"), "100", "GuildUp Discord");
        List<DiscordMemberResponse> expected = List.of(new DiscordMemberResponse(
                "300",
                "apple_account",
                "애플",
                "https://cdn.example/avatar.png"
        ));
        when(connectionService.getRequiredConnection(1L)).thenReturn(connection);
        when(discordRoleService.getMembers("100", "200")).thenReturn(expected);

        List<DiscordMemberResponse> result = queryService.getMembers(10L, 1L, "200");

        assertThat(result).isSameAs(expected);
        verify(accessService).requireCommunityAdmin(10L, 1L);
        verify(discordRoleService).getMembers("100", "200");
    }

    @Test
    void discordMemberLookupDoesNotDependOnCommunityMemberRepositories() {
        assertThat(Arrays.stream(CommunityDiscordQueryService.class.getDeclaredFields())
                .map(field -> field.getType().getName()))
                .doesNotContain(
                        CommunityMemberRepository.class.getName(),
                        CommunityMemberAccountRepository.class.getName()
                );
    }
}
