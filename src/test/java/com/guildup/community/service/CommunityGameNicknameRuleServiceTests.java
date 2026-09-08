package com.guildup.community.service;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.domain.Community;
import com.guildup.community.domain.CommunityGameNicknameRule;
import com.guildup.community.domain.CommunityUser;
import com.guildup.community.domain.DiscordCommunityConnection;
import com.guildup.community.dto.GameNicknameExtractionStatus;
import com.guildup.community.repository.CommunityGameNicknameRuleRepository;
import com.guildup.community.repository.CommunityGameRepository;
import com.guildup.community.domain.CommunityGame;
import com.guildup.community.domain.GameType;
import com.guildup.community.service.nickname.DelimitedSegmentNicknameStrategy;
import com.guildup.community.service.nickname.FullNicknameExtractionStrategy;
import com.guildup.community.service.nickname.GameNicknameRuleInferenceService;
import com.guildup.discord.service.DiscordGuildService;
import com.guildup.discord.service.DiscordMemberService;
import com.guildup.user.domain.User;
import com.guildup.user.domain.UserExternalAccount;
import com.guildup.user.repository.UserExternalAccountRepository;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommunityGameNicknameRuleServiceTests {

    private final CommunityAccessService accessService = mock(CommunityAccessService.class);
    private final DiscordCommunityConnectionService connectionService = mock(DiscordCommunityConnectionService.class);
    private final UserExternalAccountRepository userAccountRepository = mock(UserExternalAccountRepository.class);
    private final CommunityGameNicknameRuleRepository ruleRepository = mock(CommunityGameNicknameRuleRepository.class);
    private final CommunityGameRepository communityGameRepository = mock(CommunityGameRepository.class);
    private final DiscordGuildService discordGuildService = mock(DiscordGuildService.class);
    private final DiscordMemberService discordMemberService = new DiscordMemberService();
    private final GameNicknameRuleInferenceService inferenceService = new GameNicknameRuleInferenceService(List.of(
            new DelimitedSegmentNicknameStrategy(),
            new FullNicknameExtractionStrategy()
    ));
    private final CommunityGameNicknameRuleService ruleService = new CommunityGameNicknameRuleService(
            accessService, connectionService, userAccountRepository, ruleRepository,
            communityGameRepository, discordGuildService, discordMemberService, inferenceService
    );

    private final Community community = new Community("GuildUp");
    private final Guild guild = mock(Guild.class);

    @BeforeEach
    void setUp() {
        CommunityUser membership = mock(CommunityUser.class);
        when(membership.getCommunity()).thenReturn(community);
        when(accessService.requireAccess(1L, 10L)).thenReturn(membership);
        when(accessService.requireManagementAccess(1L, 10L)).thenReturn(membership);
        when(connectionService.getRequiredConnection(10L))
                .thenReturn(new DiscordCommunityConnection(community, "100", "GuildUp Discord"));
        when(discordGuildService.getGuildById("100")).thenReturn(guild);
        when(userAccountRepository.findByUserIdAndProvider(1L, ExternalAccountProvider.DISCORD))
                .thenReturn(Optional.of(new UserExternalAccount(
                        new User("관리자"), ExternalAccountProvider.DISCORD, "admin", "admin"
                )));
        when(ruleRepository.save(any(CommunityGameNicknameRule.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(communityGameRepository.findFirstByCommunityIdOrderByIdAsc(10L))
                .thenReturn(Optional.of(new CommunityGame(community, GameType.BATTLEGROUNDS_KAKAO)));
    }

    @Test
    void previewsSuccessfulAndFailedMembersWithoutAborting() {
        Member administrator = member("admin", "admin", "애플(93) sa-gwa");
        Member julmi = member("2", "julmi", "절미(95) jul-mi");
        Member potato = member("3", "potato", "감자");
        when(guild.getMembers()).thenReturn(List.of(administrator, julmi, potato));

        var result = ruleService.preview(1L, 10L, "sa-gwa");

        assertThat(result.totalMembers()).isEqualTo(3);
        assertThat(result.successfulMembers()).isEqualTo(2);
        assertThat(result.failedMembers()).isEqualTo(1);
        assertThat(result.successRate()).isEqualTo(66.7);
        assertThat(result.members()).extracting(member -> member.status())
                .containsExactly(
                        GameNicknameExtractionStatus.SUCCESS,
                        GameNicknameExtractionStatus.SUCCESS,
                        GameNicknameExtractionStatus.NEEDS_REVIEW
                );
    }

    @Test
    void savesRuleAndReturnsTheSameRuleOnNextRead() {
        Member administrator = member("admin", "admin", "애플/93/sa-gwa");
        Member julmi = member("2", "julmi", "절미/95/jul-mi");
        when(guild.getMembers()).thenReturn(List.of(administrator, julmi));
        AtomicReference<CommunityGameNicknameRule> stored = new AtomicReference<>();
        when(ruleRepository.findByCommunityIdAndGameType(10L, GameType.BATTLEGROUNDS_KAKAO))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(ruleRepository.save(any(CommunityGameNicknameRule.class))).thenAnswer(invocation -> {
            CommunityGameNicknameRule rule = invocation.getArgument(0);
            stored.set(rule);
            return rule;
        });

        var saved = ruleService.save(1L, 10L, "sa-gwa");
        var loaded = ruleService.getRule(1L, 10L);
        var savedPreview = ruleService.previewSavedRule(1L, 10L);

        assertThat(saved.configured()).isTrue();
        assertThat(loaded.configured()).isTrue();
        assertThat(loaded.sampleDiscordNickname()).isEqualTo("애플/93/sa-gwa");
        assertThat(loaded.sampleGameNickname()).isEqualTo("sa-gwa");
        assertThat(loaded.ruleDescription()).isEqualTo(saved.ruleDescription());
        assertThat(savedPreview.successfulMembers()).isEqualTo(2);
        assertThat(savedPreview.members()).extracting(member -> member.extractedGameNickname())
                .containsExactly("sa-gwa", "jul-mi");
    }

    @Test
    void analysisAndSaveRequireManagementAccess() {
        Member administrator = member("admin", "admin", "sa-gwa 애플 93");
        when(guild.getMembers()).thenReturn(List.of(administrator));

        ruleService.preview(1L, 10L, "sa-gwa");
        ruleService.save(1L, 10L, "sa-gwa");

        verify(accessService, org.mockito.Mockito.times(2)).requireManagementAccess(1L, 10L);
    }

    @Test
    void readsConfigurationStatusWithCommunityAccessWithoutLoadingDiscord() {
        when(ruleRepository.existsByCommunityIdAndGameType(10L, GameType.BATTLEGROUNDS_KAKAO))
                .thenReturn(true);

        var result = ruleService.getStatus(1L, 10L);

        assertThat(result.configured()).isTrue();
        verify(accessService).requireAccess(1L, 10L);
        verify(ruleRepository).existsByCommunityIdAndGameType(10L, GameType.BATTLEGROUNDS_KAKAO);
    }

    @Test
    void rejectsAnalysisWhenDiscordIsNotConnected() {
        when(connectionService.getRequiredConnection(10L))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Discord 서버 연결이 필요합니다."));

        assertThatThrownBy(() -> ruleService.preview(1L, 10L, "sa-gwa"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Discord 서버 연결");
    }

    @Test
    void usesTheCommunitySelectedSteamGameForNicknameRules() {
        Member administrator = member("admin", "admin", "sa-gwa 애플 93");
        when(guild.getMembers()).thenReturn(List.of(administrator));
        when(communityGameRepository.findFirstByCommunityIdOrderByIdAsc(10L))
                .thenReturn(Optional.of(new CommunityGame(community, GameType.BATTLEGROUNDS_STEAM)));

        var result = ruleService.save(1L, 10L, "sa-gwa");

        assertThat(result.gameType()).isEqualTo("BATTLEGROUNDS_STEAM");
        verify(ruleRepository).findByCommunityIdAndGameType(10L, GameType.BATTLEGROUNDS_STEAM);
    }

    @SuppressWarnings("deprecation")
    private Member member(String id, String username, String displayName) {
        Member member = mock(Member.class);
        net.dv8tion.jda.api.entities.User user = mock(net.dv8tion.jda.api.entities.User.class);
        when(member.getUser()).thenReturn(user);
        when(user.getId()).thenReturn(id);
        when(user.getName()).thenReturn(username);
        when(user.isBot()).thenReturn(false);
        when(member.getNickname()).thenReturn(displayName);
        return member;
    }
}
