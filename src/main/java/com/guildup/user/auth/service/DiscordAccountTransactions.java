package com.guildup.user.auth.service;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.discord.oauth.client.dto.DiscordApiUser;
import com.guildup.user.auth.exception.AuthException;
import com.guildup.user.domain.User;
import com.guildup.user.domain.UserExternalAccount;
import com.guildup.user.repository.UserExternalAccountRepository;
import com.guildup.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class DiscordAccountTransactions {
    private final UserExternalAccountRepository accounts;
    private final UserRepository users;

    public DiscordAccountTransactions(UserExternalAccountRepository accounts, UserRepository users) {
        this.accounts = accounts;
        this.users = users;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<User> findExisting(DiscordApiUser discord) {
        return accounts.findByProviderAndExternalUserId(ExternalAccountProvider.DISCORD, discord.id())
                .map(UserExternalAccount::getUser);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public User resolveLogin(DiscordApiUser discord) {
        return accounts.findByProviderAndExternalUserId(ExternalAccountProvider.DISCORD, discord.id())
                .map(UserExternalAccount::getUser).orElseGet(() -> create(discord));
    }

    private User create(DiscordApiUser discord) {
        String nickname = discord.globalName();
        if (nickname == null || nickname.isBlank()) nickname = discord.username();
        User user = users.save(new User(nickname));
        accounts.saveAndFlush(new UserExternalAccount(user, ExternalAccountProvider.DISCORD,
                discord.id(), discord.username()));
        return user;
    }

    /** 기존 User만 잠그고 인증수단만 추가한다. User/커뮤니티/클랜원 생성이나 병합은 없다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public User link(Long userId, DiscordApiUser discord) {
        User user = users.findForUpdate(userId).orElseThrow(() ->
                new AuthException(HttpStatus.UNAUTHORIZED, "LOGIN_REQUIRED", "로그인이 필요합니다."));
        var discordOwner = accounts.findByProviderAndExternalUserId(ExternalAccountProvider.DISCORD, discord.id());
        if (discordOwner.isPresent()) {
            if (!discordOwner.get().getUser().getId().equals(userId)) throw discordConflict();
            return user;
        }
        if (accounts.findByUserIdAndProvider(userId, ExternalAccountProvider.DISCORD).isPresent()) {
            throw new AuthException(HttpStatus.CONFLICT, "DISCORD_ALREADY_LINKED",
                    "현재 GuildUp 계정에는 다른 Discord 계정이 이미 연결되어 있습니다.");
        }
        accounts.saveAndFlush(new UserExternalAccount(user, ExternalAccountProvider.DISCORD,
                discord.id(), discord.username()));
        return user;
    }

    public static AuthException discordConflict() {
        return new AuthException(HttpStatus.CONFLICT, "DISCORD_ACCOUNT_CONFLICT",
                "이미 다른 GuildUp 계정에 연결된 Discord 계정입니다. 기존 계정으로 로그인해 주세요.");
    }
}
