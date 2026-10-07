package com.guildup.user.auth.service;

import com.guildup.user.auth.exception.AuthException;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/** 공백 제거/대소문자 정규화는 이메일에만 적용하며 비밀번호 원문은 변경하지 않는다. */
public final class CredentialPolicy {
    private static final Pattern EMAIL = Pattern.compile(
            "[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*@"
                    + "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+");
    public static final String PASSWORD_HINT = "비밀번호는 영문과 숫자를 포함한 8자 이상, UTF-8 기준 72바이트 이하여야 합니다.";

    private CredentialPolicy() {}

    public static String normalizeEmail(String email) {
        String normalized = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > 254 || !EMAIL.matcher(normalized).matches()
                || normalized.indexOf('@') > 64) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "INVALID_EMAIL", "이메일 형식이 올바르지 않습니다.");
        }
        return normalized;
    }

    public static void validatePassword(String password, String confirmation) {
        if (!isValidPassword(password)) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD", PASSWORD_HINT);
        }
        if (!password.equals(confirmation)) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "PASSWORD_MISMATCH", "비밀번호가 일치하지 않습니다.");
        }
    }

    public static boolean isValidPassword(String password) {
        return isEncodablePassword(password) && password.length() >= 8
                && password.matches("(?s).*[a-zA-Z].*") && password.matches("(?s).*[0-9].*");
    }

    /** 로그인에서는 가입 정책 변경으로 기존 비밀번호를 거부하지 않고 hash를 검증한다. */
    public static boolean isEncodablePassword(String password) {
        return password != null && !password.isEmpty() && password.length() <= 72
                && password.getBytes(StandardCharsets.UTF_8).length <= 72 && password.indexOf('\0') < 0;
    }

    public static String normalizeNickname(String nickname) {
        String normalized = nickname == null ? "" : nickname.strip();
        if (normalized.isBlank() || normalized.length() > 50
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "INVALID_NICKNAME", "닉네임은 1~50자로 입력해 주세요.");
        }
        return normalized;
    }
}
