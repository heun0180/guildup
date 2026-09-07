package com.guildup.user.auth.service;

import com.guildup.discord.oauth.client.DiscordApiClient;
import com.guildup.discord.oauth.client.dto.DiscordAccessTokenResponse;
import com.guildup.discord.oauth.client.dto.DiscordApiUser;
import org.springframework.stereotype.Service;
import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.user.domain.User;
import com.guildup.user.domain.UserExternalAccount;
import com.guildup.user.repository.UserExternalAccountRepository;
import org.springframework.transaction.annotation.Transactional;
import com.guildup.user.repository.UserRepository;

import java.util.Optional;

@Service
public class DiscordLoginService {

    private final DiscordApiClient discordApiClient;
    private final UserExternalAccountRepository userExternalAccountRepository;
    private final UserRepository userRepository;

    public DiscordLoginService(
            DiscordApiClient discordApiClient,
            UserExternalAccountRepository userExternalAccountRepository,
            UserRepository userRepository
    ) {
        this.discordApiClient = discordApiClient;
        this.userExternalAccountRepository = userExternalAccountRepository;
        this.userRepository = userRepository;
    }

    /**
     * Discord 인증 코드를 access token으로 교환한 뒤
     * 로그인한 Discord 사용자 정보를 조회한다.
     */
    public DiscordApiUser getDiscordUser(
            String code,
            String redirectUri
    ) {
        DiscordAccessTokenResponse tokenResponse =
                discordApiClient.exchangeCode(code, redirectUri);

        return discordApiClient.getCurrentUser(
                tokenResponse.accessToken()
        );
    }

    /**
     * Discord 고유 사용자 ID로 기존 GuildUp 회원을 조회한다.
     */
    @Transactional(readOnly = true)
    public Optional<User> findExistingUser(
            DiscordApiUser discordUser
    ) {
        return userExternalAccountRepository
                .findByProviderAndExternalUserId(
                        ExternalAccountProvider.DISCORD,
                        discordUser.id()
                )
                .map(UserExternalAccount::getUser);
    }

    @Transactional
    public User findOrCreateUser(DiscordApiUser discordUser) {
        return userExternalAccountRepository
                .findByProviderAndExternalUserId(
                        ExternalAccountProvider.DISCORD,
                        discordUser.id()
                )
                .map(UserExternalAccount::getUser)
                .orElseGet(() -> createNewUser(discordUser));
    }

    @Transactional
    public User createNewUser(DiscordApiUser discordUser) {
        String nickname = discordUser.globalName();

        if (nickname == null || nickname.isBlank()) {
            nickname = discordUser.username();
        }

        User user = userRepository.save(new User(nickname));

        UserExternalAccount account = new UserExternalAccount(
                user,
                ExternalAccountProvider.DISCORD,
                discordUser.id(),
                discordUser.username()
        );

        userExternalAccountRepository.save(account);

        return user;
    }

    @Transactional(readOnly = true)
    public Optional<User> findUserById(Long userId) {
        return userRepository.findById(userId);
    }
}