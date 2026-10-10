package com.guildup.developer.config;

import com.guildup.user.auth.service.CurrentUserSession;
import com.guildup.monitoring.web.AccessDeniedMonitoringFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** 모든 개발자 API 요청을 컨트롤러 진입 전에 보호한다. */
@Component
public class DeveloperAccessInterceptor implements HandlerInterceptor {
    private final DeveloperAccessService access;

    public DeveloperAccessInterceptor(DeveloperAccessService access) {
        this.access = access;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Long userId = CurrentUserSession.requireUserId(request.getSession(false));
        try { access.requireSystemAdmin(userId); }
        catch (org.springframework.web.server.ResponseStatusException denied) {
            if (denied.getStatusCode().value() == 403)
                AccessDeniedMonitoringFilter.mark(request, AccessDeniedMonitoringFilter.Denial.SYSTEM_ADMIN_REQUIRED);
            throw denied;
        }
        return true;
    }
}
