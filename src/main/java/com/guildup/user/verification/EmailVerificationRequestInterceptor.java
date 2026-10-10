package com.guildup.user.verification;

import com.guildup.monitoring.domain.MonitoringEventCode;
import com.guildup.user.auth.controller.AuthController;
import com.guildup.user.auth.security.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import java.util.Set;

@Component
public class EmailVerificationRequestInterceptor implements HandlerInterceptor {
    private final EmailVerificationIpLimiter limiter;
    private final ClientIpResolver ips;
    private final LoginIdentityHasher hasher;
    private final EmailVerificationEvents events;
    public EmailVerificationRequestInterceptor(EmailVerificationIpLimiter limiter, ClientIpResolver ips,
                                              LoginIdentityHasher hasher, EmailVerificationEvents events) {
        this.limiter = limiter; this.ips = ips; this.hasher = hasher; this.events = events;
    }
    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"POST".equals(request.getMethod()) || !(handler instanceof HandlerMethod method)) return true;
        boolean verification = EmailVerificationController.class.isAssignableFrom(method.getBeanType());
        boolean registration = AuthController.class.isAssignableFrom(method.getBeanType())
                && Set.of("signup", "addCredential").contains(method.getMethod().getName());
        if (!verification && !registration) return true;
        boolean confirm = verification && method.getMethod().getName().equals("confirm");
        String key = hasher.hash("email-verification-ip", ips.rateLimitAddress(request));
        long retry = limiter.reserve(key, confirm);
        if (retry > 0) {
            events.record(MonitoringEventCode.EMAIL_VERIFICATION_ABUSE, null);
            throw new EmailVerificationRateLimitedException(retry);
        }
        return true;
    }
}
