package com.guildup.user.auth.service;

import com.guildup.user.auth.dto.CredentialRequest;
import com.guildup.user.auth.dto.EmailLoginRequest;
import com.guildup.user.auth.dto.SignupRequest;
import com.guildup.user.auth.exception.AuthException;
import com.guildup.user.domain.User;
import com.guildup.user.repository.UserCredentialRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class CredentialAuthService {
    private final CredentialTransactions transactions;
    private final UserCredentialRepository credentials;
    private final PasswordEncoder encoder;
    private final String dummyHash;

    public CredentialAuthService(CredentialTransactions transactions, UserCredentialRepository credentials,
                                 PasswordEncoder encoder) {
        this.transactions = transactions;
        this.credentials = credentials;
        this.encoder = encoder;
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    public User register(SignupRequest request) {
        String email = CredentialPolicy.normalizeEmail(request.email());
        CredentialPolicy.validatePassword(request.password(), request.passwordConfirmation());
        String nickname = CredentialPolicy.normalizeNickname(request.nickname());
        String hash = encoder.encode(request.password());
        try {
            return transactions.register(email, hash, nickname);
        } catch (DataIntegrityViolationException exception) {
            // 신규 User와 인증정보는 함께 롤백된다. 실패한 트랜잭션에서는 재조회하지 않는다.
            if (credentials.existsByEmail(email)) throw CredentialTransactions.duplicateEmail();
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public User login(EmailLoginRequest request) {
        String email;
        try {
            email = CredentialPolicy.normalizeEmail(request.email());
        } catch (AuthException exception) {
            encoder.matches("invalid-login", dummyHash);
            throw invalidLogin();
        }
        var credential = credentials.findByEmail(email);
        boolean validInput = CredentialPolicy.isEncodablePassword(request.password());
        boolean matches = encoder.matches(validInput ? request.password() : "invalid-login",
                credential.map(value -> value.getPasswordHash()).orElse(dummyHash));
        if (!validInput || credential.isEmpty() || !matches || !credential.get().getUser().isActive()) throw invalidLogin();
        // 이메일 인증 전에도 로그인은 허용한다. 인증됨으로 표시하거나 권한 근거로 삼지 않는다.
        return credential.get().getUser();
    }

    public User addCredential(Long userId, CredentialRequest request) {
        String email = CredentialPolicy.normalizeEmail(request.email());
        CredentialPolicy.validatePassword(request.password(), request.passwordConfirmation());
        String hash = encoder.encode(request.password());
        try {
            return transactions.addCredential(userId, email, hash);
        } catch (DataIntegrityViolationException exception) {
            if (credentials.existsByUserId(userId)) throw CredentialTransactions.credentialAlreadyAdded();
            if (credentials.existsByEmail(email)) throw CredentialTransactions.duplicateEmail();
            throw exception;
        }
    }

    private AuthException invalidLogin() {
        return new AuthException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다.");
    }
}
