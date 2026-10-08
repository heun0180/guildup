package com.guildup.user.auth.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.ForwardedHeaderFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.*;

/** Own the trust boundary before any request wrapper changes getRemoteAddr(). */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TrustedProxyFilter extends OncePerRequestFilter {
    private final ClientIpResolver resolver;
    private final ForwardedHeaderFilter forwarded = new ForwardedHeaderFilter();

    public TrustedProxyFilter(ClientIpResolver resolver,
                              @Value("${server.forward-headers-strategy:none}") String strategy) {
        if (!"none".equalsIgnoreCase(strategy)) {
            throw new IllegalArgumentException("Use server.forward-headers-strategy=none with the trusted proxy filter");
        }
        this.resolver = resolver;
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        String client = resolver.resolve(request);
        request.setAttribute(ClientIpResolver.CLIENT_IP, client);
        boolean trusted = resolver.isTrustedPeer(request);
        Map<String, String> safe = new LinkedHashMap<>();
        if (trusted) {
            if (!client.equals("unknown")) safe.put("x-forwarded-for", client);
            String proto = ClientIpResolver.singleHeader(request, "X-Forwarded-Proto");
            if ("https".equals(proto) || "http".equals(proto)) safe.put("x-forwarded-proto", proto);
            String port = ClientIpResolver.singleHeader(request, "X-Forwarded-Port");
            if (port != null && port.matches("[0-9]{1,5}") && Integer.parseInt(port) > 0 && Integer.parseInt(port) <= 65535) safe.put("x-forwarded-port", port);
            // Host comes from Nginx's fixed Host header, never Forwarded.host / X-Forwarded-Host.
        }
        var sanitized = new HttpServletRequestWrapper(request) {
            private boolean isForwarded(String name) {
                return name.equalsIgnoreCase("Forwarded") || name.toLowerCase(Locale.ROOT).startsWith("x-forwarded-")
                        || name.equalsIgnoreCase("X-Real-IP");
            }
            @Override public String getHeader(String name) {
                return isForwarded(name) ? safe.get(name.toLowerCase(Locale.ROOT)) : super.getHeader(name);
            }
            @Override public Enumeration<String> getHeaders(String name) {
                if (!isForwarded(name)) return super.getHeaders(name);
                String value = getHeader(name);
                return Collections.enumeration(value == null ? List.of() : List.of(value));
            }
            @Override public Enumeration<String> getHeaderNames() {
                var names = new LinkedHashSet<String>();
                var original = super.getHeaderNames();
                if (original != null) while (original.hasMoreElements()) {
                    String name = original.nextElement();
                    if (!isForwarded(name)) names.add(name);
                }
                names.addAll(safe.keySet());
                return Collections.enumeration(names);
            }
        };
        forwarded.doFilter(sanitized, response, chain);
    }
}
