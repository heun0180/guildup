package com.guildup.user.verification;

import com.guildup.user.auth.exception.AuthException;
import com.guildup.user.auth.service.CurrentUserSession;
import jakarta.servlet.http.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

/** 커뮤니티/게임 기능의 선택된 경계에만 적용. 계정/공개 안내/고객지원/로그인은 유지한다. */
public class EmailVerificationAccessInterceptor implements HandlerInterceptor {
    private final EmailVerificationAccessPolicy policy;
    public EmailVerificationAccessInterceptor(EmailVerificationAccessPolicy policy) { this.policy = policy; }
    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        var session = request.getSession(false);
        if (session != null && session.getAttribute(CurrentUserSession.USER_ID) instanceof Long id && policy.restricted(id))
            throw new AuthException(HttpStatus.FORBIDDEN, "EMAIL_VERIFICATION_REQUIRED", "이메일 인증 후 이용할 수 있습니다. 계정 설정에서 이메일 인증을 완료해 주세요.");
        return true;
    }
}
