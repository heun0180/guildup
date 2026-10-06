package com.guildup.user.auth;

import org.springframework.test.context.ActiveProfiles;

/** Verify the production profile emits Secure cookies while the default local profile supports HTTP. */
@ActiveProfiles("prod")
class SessionSecureCookieIntegrationTests extends SessionCookieIntegrationTests {
    @Override
    protected boolean secureCookieExpected() { return true; }
}
