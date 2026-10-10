package com.guildup.user.auth.security;

import com.guildup.monitoring.web.RequestLogContextFilter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;

class AuthenticationRequestIdTests {
    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/login", "/api/%61uth/login", "/api/auth;matrix=1/login", "/api/auth/discord/callback",
            "/api/account", "/api/developer/dashboard", "/api/communities/12"})
    void encodedAndMatrixAuthenticationPathsAlwaysUseServerGeneratedDiagnosticId(String path) throws Exception {
        var request = new MockHttpServletRequest("POST", path);
        request.addHeader("X-Request-ID", "private-password-in-header");
        var response = new MockHttpServletResponse();
        new RequestLogContextFilter().doFilter(request, response, (req, res) -> {});
        assertThat(response.getHeader("X-Request-ID")).matches("[a-f0-9-]{36}")
                .isNotEqualTo("private-password-in-header")
                .isEqualTo(request.getAttribute(RequestLogContextFilter.REQUEST_ID));
    }
}
