package com.guildup.user.auth.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class ClientIpResolverTests {
    final TrustedProxyProperties proxy = new TrustedProxyProperties();
    final LoginProtectionProperties login = new LoginProtectionProperties();
    ClientIpResolver resolver() { return new ClientIpResolver(proxy, login); }
    MockHttpServletRequest request(String peer) { var request = new MockHttpServletRequest(); request.setRemoteAddr(peer); return request; }

    @Test void defaultsToSocketIpIgnoringEveryForgedHeader() {
        var request = request("203.0.113.10");
        request.addHeader("X-Forwarded-For", "198.51.100.1"); request.addHeader("X-Real-IP", "198.51.100.2");
        request.addHeader("Forwarded", "for=198.51.100.3;proto=https");
        assertThat(resolver().resolve(request)).isEqualTo("203.0.113.10");
    }
    @Test void explicitTrustedChainStopsAtFirstUntrustedHopAndIgnoresSpoofedLeftSide() {
        proxy.setTrustedProxies(List.of("127.0.0.1/32", "10.0.0.0/24"));
        var request = request("127.0.0.1");
        request.addHeader("X-Forwarded-For", "198.51.100.99, 203.0.113.10, 10.0.0.2");
        assertThat(resolver().resolve(request)).isEqualTo("203.0.113.10");
        var direct = request("203.0.113.10"); direct.addHeader("X-Forwarded-For", "198.51.100.99");
        assertThat(resolver().resolve(direct)).isEqualTo("203.0.113.10");
    }
    @Test void malformedAndDuplicateHeaderChainsFallBackToSocketWithoutDns() {
        proxy.setTrustedProxies(List.of("127.0.0.1"));
        for (String value : new String[]{"localhost", "203.0.113.1, unknown", "203.0.113.1,", "012.1.1.1", "1.2.3.999", "2001:db8::1%eth0", "1.2.3.4,".repeat(20)}) {
            var request = request("127.0.0.1"); request.addHeader("X-Forwarded-For", value);
            assertThat(resolver().resolve(request)).isEqualTo("127.0.0.1");
        }
        var request = request("127.0.0.1"); request.addHeader("X-Forwarded-For", "1.2.3.4"); request.addHeader("X-Forwarded-For", "5.6.7.8");
        assertThat(resolver().resolve(request)).isEqualTo("127.0.0.1");
    }
    @Test void forwardedSupportsQuotedIpv6AndIpv4PortsAndRejectsUnknownOrDuplicateFor() {
        proxy.setTrustedProxies(List.of("::1")); proxy.setClientIpHeader(TrustedProxyProperties.ClientIpHeader.FORWARDED);
        var request = request("::1"); request.addHeader("Forwarded", "for=\"[2001:db8::1]:443\";proto=https");
        assertThat(resolver().resolve(request)).isEqualTo("2001:db8:0:0:0:0:0:1");
        var ipv4 = request("::1"); ipv4.addHeader("Forwarded", "for=\"203.0.113.1:443\"");
        assertThat(resolver().resolve(ipv4)).isEqualTo("203.0.113.1");
        for (String value : new String[]{"for=unknown", "for=_hidden", "for=203.0.113.1;for=198.51.100.1", "for=\"[2001:db8::1]:99999\""}) {
            var invalid = request("::1"); invalid.addHeader("Forwarded", value);
            assertThat(resolver().resolve(invalid)).isEqualTo("0:0:0:0:0:0:0:1");
        }
    }
    @Test void xRealIpRequiresExplicitSelectionAndTrustedSocketAndHasNoFallback() {
        proxy.setTrustedProxies(List.of("127.0.0.1"));
        var request = request("127.0.0.1"); request.addHeader("X-Real-IP", "203.0.113.1");
        assertThat(resolver().resolve(request)).isEqualTo("127.0.0.1");
        proxy.setClientIpHeader(TrustedProxyProperties.ClientIpHeader.X_REAL_IP);
        assertThat(resolver().resolve(request)).isEqualTo("203.0.113.1");
    }
    @Test void equivalentIpv6AndIpv4MappedIpv6CannotBypassIdentityAndIpv6PrivacyAddressesSharePrefix() {
        var resolver = resolver();
        assertThat(resolver.rateLimitAddress(request("2001:db8::1"))).isEqualTo(resolver.rateLimitAddress(request("2001:0db8:0000:0000::abcd")));
        assertThat(resolver.rateLimitAddress(request("::ffff:203.0.113.1"))).isEqualTo(resolver.rateLimitAddress(request("203.0.113.1")));
        assertThat(resolver.rateLimitAddress(request("2001:db8:1::1"))).isNotEqualTo(resolver.rateLimitAddress(request("2001:db8:2::1")));
    }
    @Test void trustedIpv6CidrsWorkAndOverbroadOrHostnameTrustIsRejected() {
        proxy.setTrustedProxies(List.of("2001:db8:ffff::/48"));
        var request = request("2001:db8:ffff::1"); request.addHeader("X-Forwarded-For", "203.0.113.1");
        assertThat(resolver().resolve(request)).isEqualTo("203.0.113.1");
        for (String value : new String[]{"0.0.0.0/0", "::/0", "localhost", "1.2.3.4/33"}) {
            proxy.setTrustedProxies(List.of(value)); assertThatThrownBy(this::resolver).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void filterRemovesUntrustedSchemeHostAndAddressBeforeOAuthSeesRequest() throws Exception {
        var request = request("203.0.113.10"); request.setScheme("http"); request.setServerName("guildup.test");
        request.addHeader("Forwarded", "for=198.51.100.1;host=evil.test;proto=https");
        request.addHeader("X-Forwarded-Host", "evil.test"); request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-For", "198.51.100.1");
        AtomicReference<HttpServletRequest> filtered = new AtomicReference<>();
        new TrustedProxyFilter(resolver(), "none").doFilter(request, new MockHttpServletResponse(), (req, res) -> filtered.set((HttpServletRequest) req));
        assertThat(filtered.get().getRemoteAddr()).isEqualTo("203.0.113.10");
        assertThat(filtered.get().getScheme()).isEqualTo("http"); assertThat(filtered.get().getServerName()).isEqualTo("guildup.test");
        assertThat(filtered.get().getHeader("Forwarded")).isNull(); assertThat(filtered.get().getHeader("X-Forwarded-For")).isNull();
    }
    @Test void trustedNginxPreservesHttpsCallbackHostAndVerifiedClientIp() throws Exception {
        proxy.setTrustedProxies(List.of("127.0.0.1"));
        var request = request("127.0.0.1"); request.setServerName("guildup.test"); request.setServerPort(80);
        request.addHeader("Host", "guildup.test"); request.addHeader("X-Forwarded-Host", "evil.test");
        request.addHeader("X-Forwarded-Proto", "https"); request.addHeader("X-Forwarded-Port", "443");
        request.addHeader("X-Forwarded-For", "203.0.113.1");
        new TrustedProxyFilter(resolver(), "none").doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            var actual = (HttpServletRequest) req;
            assertThat(actual.getScheme()).isEqualTo("https"); assertThat(actual.isSecure()).isTrue();
            assertThat(actual.getServerName()).isEqualTo("guildup.test"); assertThat(actual.getServerPort()).isEqualTo(443);
            assertThat(actual.getRemoteAddr()).isEqualTo("203.0.113.1");
            assertThat(actual.getAttribute(ClientIpResolver.CLIENT_IP)).isEqualTo("203.0.113.1");
        });
        assertThatThrownBy(() -> new TrustedProxyFilter(resolver(), "framework")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrustedProxyFilter(resolver(), "native")).isInstanceOf(IllegalArgumentException.class);
    }
}
