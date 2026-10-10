package com.guildup.user.reset;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Applies even to failed API responses and the dedicated HTML entry when served by Spring. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class PasswordResetPrivacyFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.equals("/password-reset.html") || path.equals("/forgot-password.html") || path.startsWith("/api/auth/password-reset")) {
            response.setHeader("Referrer-Policy", "no-referrer");
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Robots-Tag", "noindex, nofollow");
        }
        chain.doFilter(request, response);
    }
}
