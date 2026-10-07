package com.guildup.user.auth.service;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.user.auth.exception.AuthException;
import com.guildup.user.domain.User;
import com.guildup.user.repository.UserCredentialRepository;
import com.guildup.user.repository.UserExternalAccountRepository;
import com.guildup.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 개인 로그인 계정 연결만 다룬다. 커뮤니티 서버/클랜원 연결과는 독립적이다. */
@Service
public class DiscordConnectionService {
    private final UserRepository users;
    private final UserCredentialRepository credentials;
    private final UserExternalAccountRepository accounts;

    public DiscordConnectionService(UserRepository users, UserCredentialRepository credentials,
                                    UserExternalAccountRepository accounts) {
        this.users = users;
        this.credentials = credentials;
        this.accounts = accounts;
    }

    @Transactional
    public void disconnect(Long userId) {
        // 연결 추가/이메일 추가/탈퇴와 직렬화해 마지막 로그인 수단을 항상 유지한다.
        users.findForUpdate(userId).filter(User::isActive).orElseThrow(() ->
                new AuthException(HttpStatus.UNAUTHORIZED, "LOGIN_REQUIRED", "로그인이 필요합니다."));
        var discord = accounts.findByUserIdAndProvider(userId, ExternalAccountProvider.DISCORD);
        if (discord.isEmpty()) return;
        if (!credentials.existsByUserId(userId)) {
            throw new AuthException(HttpStatus.CONFLICT, "LAST_LOGIN_METHOD", "Discord 연결을 해제하려면 먼저 이메일 로그인을 추가해 주세요.");
        }
        accounts.delete(discord.get());
    }
}
