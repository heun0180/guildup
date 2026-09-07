package com.guildup.community.config;

import com.guildup.community.service.CommunityAccessService;
import com.guildup.user.auth.service.CurrentUserSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import java.util.Map;

/** Community 하위의 모든 API와 기존 guild ID 직접 조회 경로를 보호한다. */
@Component
public class CommunityAccessInterceptor implements HandlerInterceptor {
    private final CommunityAccessService access;
    public CommunityAccessInterceptor(CommunityAccessService access) { this.access = access; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Long userId = CurrentUserSession.requireUserId(request.getSession(false));
        @SuppressWarnings("unchecked")
        Map<String, String> variables = (Map<String, String>) request.getAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (variables != null && variables.containsKey("communityId")) {
            try {
                access.requireAccess(userId, Long.valueOf(variables.get("communityId")));
            } catch (NumberFormatException exception) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid community ID");
            }
        }
        if (variables != null && variables.containsKey("guildId")) {
            access.requireGuildAccess(userId, variables.get("guildId"));
        }
        return true;
    }
}
