package com.guildup.user.auth.service;

import com.guildup.discord.oauth.client.DiscordApiClient;
import com.guildup.discord.oauth.client.dto.DiscordApiUser;
import com.guildup.user.domain.User;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** Discord 인증, 기존 사용자 조회/생성과 로그인 수단 연결을 담당한다. */
@Service
public class DiscordLoginService {
    private final DiscordApiClient client;
    private final DiscordAccountTransactions transactions;

    public DiscordLoginService(DiscordApiClient client, DiscordAccountTransactions transactions) {
        this.client = client;
        this.transactions = transactions;
    }

    public DiscordApiUser getDiscordUser(String code, String redirectUri) {
        var token = client.exchangeCode(code, redirectUri);
        return client.getCurrentUser(token.accessToken());
    }

    public Optional<User> findExistingUser(DiscordApiUser discord) {
        return transactions.findExisting(discord);
    }

    public User findOrCreateUser(DiscordApiUser discord) {
        try {
            return transactions.resolveLogin(discord);
        } catch (DataIntegrityViolationException exception) {
            // 패자의 User는 외부 계정과 함께 롤백된다. 승자는 새 트랜잭션에서 조회한다.
            return transactions.findExisting(discord).orElseThrow(() -> exception);
        }
    }

    public User linkAccount(Long userId, DiscordApiUser discord) {
        try {
            return transactions.link(userId, discord);
        } catch (DataIntegrityViolationException exception) {
            // 경쟁의 승자를 재조회: 같은 연결은 성공, 다른 User는 사용자용 충돌이다.
            return transactions.link(userId, discord);
        }
    }
}
