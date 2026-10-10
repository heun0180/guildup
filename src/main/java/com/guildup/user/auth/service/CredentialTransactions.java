package com.guildup.user.auth.service;

import com.guildup.user.auth.exception.AuthException;
import com.guildup.user.domain.User;
import com.guildup.user.domain.UserCredential;
import com.guildup.user.repository.UserCredentialRepository;
import com.guildup.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** UNIQUE 실패한 트랜잭션 밖에서 사용자용 오류로 바꾸도록 쓰기 경계를 분리한다. */
@Service
public class CredentialTransactions {
    private final UserRepository users;
    private final UserCredentialRepository credentials;
    private final com.guildup.user.verification.EmailVerificationService verification;

    public CredentialTransactions(UserRepository users, UserCredentialRepository credentials,
                                  com.guildup.user.verification.EmailVerificationService verification) {
        this.users = users;
        this.credentials = credentials;
        this.verification = verification;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public User register(String email, String passwordHash, String nickname) {
        if (credentials.existsByEmail(email)) throw duplicateEmail();
        User user = users.save(new User(nickname));
        var credential = new UserCredential(user, email, passwordHash);
        credential.requireEmailVerification();
        credentials.saveAndFlush(credential);
        verification.issueInitial(credential);
        return user;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public User addCredential(Long userId, String email, String passwordHash) {
        User user = users.findForUpdate(userId).filter(com.guildup.user.domain.User::isActive).orElseThrow(() ->
                new AuthException(HttpStatus.UNAUTHORIZED, "LOGIN_REQUIRED", "로그인이 필요합니다."));
        if (credentials.existsByUserId(userId)) throw credentialAlreadyAdded();
        if (credentials.existsByEmail(email)) throw duplicateEmail();
        var credential = credentials.saveAndFlush(new UserCredential(user, email, passwordHash));
        verification.issueInitial(credential);
        return user;
    }

    public static AuthException duplicateEmail() {
        return new AuthException(HttpStatus.CONFLICT, "EMAIL_ALREADY_USED", "이미 사용 중인 이메일입니다.");
    }

    public static AuthException credentialAlreadyAdded() {
        return new AuthException(HttpStatus.CONFLICT, "CREDENTIAL_ALREADY_EXISTS", "이 계정에는 이메일 로그인이 이미 등록되어 있습니다.");
    }
}
