package com.guildup.user.profile.service;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.user.auth.exception.AuthException;
import com.guildup.user.auth.service.AuthSessionService;
import com.guildup.user.auth.service.CredentialPolicy;
import com.guildup.user.auth.service.CurrentUserSession;
import com.guildup.user.domain.User;
import com.guildup.user.profile.dto.ProfileUpdateRequest;
import com.guildup.user.repository.UserExternalAccountRepository;
import com.guildup.user.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

@Service
public class AccountProfileService {
    private final AuthSessionService sessions;
    private final UserRepository users;
    private final UserExternalAccountRepository externalAccounts;
    private final Clock clock;

    public AccountProfileService(AuthSessionService sessions, UserRepository users,
                                 UserExternalAccountRepository externalAccounts, Clock clock) {
        this.sessions = sessions;
        this.users = users;
        this.externalAccounts = externalAccounts;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ProfileResponse get(HttpSession session) {
        return response(sessions.requireUser(session));
    }

    @Transactional
    public ProfileResponse update(HttpSession session, ProfileUpdateRequest request) {
        // 인증수단 변경/탈퇴와 같은 User 잠금을 사용해 탈퇴 후 개인정보 복원을 막는다.
        User user = users.findForUpdate(CurrentUserSession.requireUserId(session)).filter(User::isActive)
                .orElseThrow(() -> new AuthException(HttpStatus.UNAUTHORIZED, "LOGIN_REQUIRED", "로그인이 필요합니다."));
        String nickname = request.nicknameProvided() ? CredentialPolicy.normalizeNickname(request.nickname()) : user.getNickname();
        LocalDate birthDate = request.birthDateProvided() ? parseBirthDate(request.birthDate()) : user.getBirthDate();
        user.updateProfile(nickname, birthDate);
        return response(user);
    }

    private LocalDate parseBirthDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalidBirthDate();
            LocalDate date = LocalDate.parse(value);
            if (date.getYear() < 1 || date.isAfter(LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))))) throw invalidBirthDate();
            return date;
        } catch (DateTimeParseException exception) {
            throw invalidBirthDate();
        }
    }

    private AuthException invalidBirthDate() {
        return new AuthException(HttpStatus.BAD_REQUEST, "INVALID_BIRTH_DATE", "생년월일은 미래가 아닌 실제 날짜로 입력해 주세요.");
    }

    private ProfileResponse response(User user) {
        String avatarUrl = externalAccounts.findByUserIdAndProvider(user.getId(), ExternalAccountProvider.DISCORD)
                .map(account -> account.getExternalAvatarUrl()).orElse(null);
        return new ProfileResponse(user.getNickname(), user.getBirthDate(), avatarUrl);
    }

    public record ProfileResponse(String nickname, LocalDate birthDate, String avatarUrl) {}
}
