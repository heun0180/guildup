package com.guildup.user.auth.service;

import com.guildup.discord.oauth.client.DiscordApiClient;
import com.guildup.discord.oauth.client.dto.DiscordAccessTokenResponse;
import com.guildup.discord.oauth.client.dto.DiscordApiUser;
import org.junit.jupiter.api.Test;
import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.user.domain.User;
import com.guildup.user.domain.UserExternalAccount;
import com.guildup.user.repository.UserExternalAccountRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.guildup.user.repository.UserRepository;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;



class DiscordLoginServiceTests {

    private final DiscordApiClient discordApiClient =
            mock(DiscordApiClient.class);

    private final UserExternalAccountRepository userExternalAccountRepository =
            mock(UserExternalAccountRepository.class);

    private final UserRepository userRepository = mock(UserRepository.class);

    private final DiscordLoginService discordLoginService =
            new DiscordLoginService(
                    discordApiClient,
                    userExternalAccountRepository,
                    userRepository
            );



    @Test
    void exchangesCodeAndGetsDiscordUser() {
        DiscordAccessTokenResponse tokenResponse =
                new DiscordAccessTokenResponse(
                        "login-access-token",
                        "Bearer",
                        3600,
                        "identify"
                );

        DiscordApiUser discordUser =
                new DiscordApiUser(
                        "123456789",
                        "apple",
                        "애플",
                        "avatar-hash"
                );

        when(discordApiClient.exchangeCode(
                "authorization-code",
                "http://localhost:8080/api/auth/discord/callback"
        )).thenReturn(tokenResponse);

        when(discordApiClient.getCurrentUser(
                "login-access-token"
        )).thenReturn(discordUser);

        DiscordApiUser result = discordLoginService.getDiscordUser(
                "authorization-code",
                "http://localhost:8080/api/auth/discord/callback"
        );

        assertThat(result).isSameAs(discordUser);
        assertThat(result.id()).isEqualTo("123456789");
        assertThat(result.username()).isEqualTo("apple");
        assertThat(result.globalName()).isEqualTo("애플");

        verify(discordApiClient).exchangeCode(
                "authorization-code",
                "http://localhost:8080/api/auth/discord/callback"
        );

        verify(discordApiClient).getCurrentUser(
                "login-access-token"
        );
    }

    @Test
    void findsExistingUserByDiscordUserId() {
        DiscordApiUser discordUser =
                new DiscordApiUser(
                        "123456789",
                        "apple",
                        "애플",
                        "avatar-hash"
                );

        User user = new User("애플");

        UserExternalAccount account =
                new UserExternalAccount(
                        user,
                        ExternalAccountProvider.DISCORD,
                        "123456789",
                        "apple"
                );

        when(userExternalAccountRepository
                .findByProviderAndExternalUserId(
                        ExternalAccountProvider.DISCORD,
                        "123456789"
                ))
                .thenReturn(Optional.of(account));

        Optional<User> result =
                discordLoginService.findExistingUser(discordUser);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow()).isSameAs(user);

        verify(userExternalAccountRepository)
                .findByProviderAndExternalUserId(
                        ExternalAccountProvider.DISCORD,
                        "123456789"
                );
    }

    @Test
    void returnsEmptyWhenDiscordUserIsNotRegistered() {
        DiscordApiUser discordUser =
                new DiscordApiUser(
                        "999999999",
                        "new-user",
                        "신규 사용자",
                        null
                );

        when(userExternalAccountRepository
                .findByProviderAndExternalUserId(
                        ExternalAccountProvider.DISCORD,
                        "999999999"
                ))
                .thenReturn(Optional.empty());

        Optional<User> result =
                discordLoginService.findExistingUser(discordUser);

        assertThat(result).isEmpty();

        verify(userExternalAccountRepository)
                .findByProviderAndExternalUserId(
                        ExternalAccountProvider.DISCORD,
                        "999999999"
                );
    }

    @Test
    void createsUserAndDiscordExternalAccountInOrder() {
        DiscordApiUser discordUser = new DiscordApiUser(
                "123456789",
                "apple",
                "애플",
                "avatar-hash"
        );

        when(userRepository.save(any(User.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(userExternalAccountRepository.save(any(UserExternalAccount.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        User result = discordLoginService.createNewUser(discordUser);

        ArgumentCaptor<User> userCaptor =
                ArgumentCaptor.forClass(User.class);

        ArgumentCaptor<UserExternalAccount> accountCaptor =
                ArgumentCaptor.forClass(UserExternalAccount.class);

        InOrder order =
                inOrder(userRepository, userExternalAccountRepository);

        order.verify(userRepository).save(userCaptor.capture());
        order.verify(userExternalAccountRepository)
                .save(accountCaptor.capture());

        User savedUser = userCaptor.getValue();
        UserExternalAccount savedAccount = accountCaptor.getValue();

        assertThat(result).isSameAs(savedUser);
        assertThat(savedUser.getNickname()).isEqualTo("애플");
        assertThat(savedAccount.getUser()).isSameAs(savedUser);
        assertThat(savedAccount.getProvider())
                .isEqualTo(ExternalAccountProvider.DISCORD);
        assertThat(savedAccount.getExternalUserId())
                .isEqualTo("123456789");
        assertThat(savedAccount.getExternalUsername())
                .isEqualTo("apple");
    }

    @Test
    void usesDiscordUsernameWhenGlobalNameIsMissing() {
        DiscordApiUser discordUser = new DiscordApiUser(
                "999999999",
                "fallback-name",
                null,
                null
        );

        when(userRepository.save(any(User.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(userExternalAccountRepository.save(any(UserExternalAccount.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        User result = discordLoginService.createNewUser(discordUser);

        assertThat(result.getNickname()).isEqualTo("fallback-name");
    }

    @Test
    void returnsExistingUserWithoutCreatingNewUser() {
        DiscordApiUser discordUser = new DiscordApiUser(
                "123456789",
                "apple",
                "애플",
                "avatar-hash"
        );

        User existingUser = new User("기존 사용자");

        UserExternalAccount account = new UserExternalAccount(
                existingUser,
                ExternalAccountProvider.DISCORD,
                "123456789",
                "apple"
        );

        when(userExternalAccountRepository
                .findByProviderAndExternalUserId(
                        ExternalAccountProvider.DISCORD,
                        "123456789"
                ))
                .thenReturn(Optional.of(account));

        User result =
                discordLoginService.findOrCreateUser(discordUser);

        assertThat(result).isSameAs(existingUser);

        verify(userRepository, never())
                .save(any(User.class));

        verify(userExternalAccountRepository, never())
                .save(any(UserExternalAccount.class));
    }

    @Test
    void createsNewUserWhenDiscordAccountDoesNotExist() {
        DiscordApiUser discordUser = new DiscordApiUser(
                "999999999",
                "new-user",
                "신규 사용자",
                null
        );

        when(userExternalAccountRepository
                .findByProviderAndExternalUserId(
                        ExternalAccountProvider.DISCORD,
                        "999999999"
                ))
                .thenReturn(Optional.empty());

        when(userRepository.save(any(User.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(userExternalAccountRepository
                .save(any(UserExternalAccount.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        User result =
                discordLoginService.findOrCreateUser(discordUser);

        assertThat(result.getNickname()).isEqualTo("신규 사용자");

        verify(userRepository)
                .save(any(User.class));

        verify(userExternalAccountRepository)
                .save(any(UserExternalAccount.class));
    }

    @Test
    void findsLoginUserById() {
        User user = new User("애플");

        when(userRepository.findById(10L))
                .thenReturn(Optional.of(user));

        Optional<User> result =
                discordLoginService.findUserById(10L);

        assertThat(result).containsSame(user);

        verify(userRepository).findById(10L);
    }

    @Test
    void returnsEmptyWhenLoginUserDoesNotExist() {
        when(userRepository.findById(999L))
                .thenReturn(Optional.empty());

        Optional<User> result =
                discordLoginService.findUserById(999L);

        assertThat(result).isEmpty();

        verify(userRepository).findById(999L);
    }
}