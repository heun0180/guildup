package com.guildup.support;

import com.guildup.user.auth.service.CurrentUserSession;
import com.guildup.user.auth.service.SessionCsrfTokens;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;

import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Existing business-flow tests act as a legitimate client. Security regressions do not import this. */
@TestConfiguration(proxyBeanMethods = false)
public class SessionCsrfTestClient {
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @org.springframework.context.annotation.Import(SessionCsrfTestClient.class)
    public @interface WithSessionCsrf {}

    @Bean
    MockMvcBuilderCustomizer sessionCsrfClient() {
        return builder -> builder.defaultRequest(get("/").with(request -> {
            var session = request.getSession(false);
            if (!Set.of("GET", "HEAD", "OPTIONS", "TRACE").contains(request.getMethod())
                    && session != null && session.getAttribute(CurrentUserSession.USER_ID) instanceof Long
                    && request.getHeader(SessionCsrfTokens.HEADER) == null) {
                request.addHeader(SessionCsrfTokens.HEADER, SessionCsrfTokens.getOrCreate(session));
            }
            return request;
        }));
    }
}
