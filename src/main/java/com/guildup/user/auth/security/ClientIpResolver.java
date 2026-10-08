package com.guildup.user.auth.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;

/** Literal parsing only: DNS, scoped IPv6, obfuscated Forwarded identifiers and malformed chains are rejected. */
@Component
public class ClientIpResolver {
    public static final String CLIENT_IP = ClientIpResolver.class.getName() + ".clientIp";
    private final List<Network> trusted;
    private final TrustedProxyProperties properties;
    private final int ipv6Prefix;

    public ClientIpResolver(TrustedProxyProperties properties, LoginProtectionProperties login) {
        this.properties = properties;
        ipv6Prefix = login.getIpv6PrefixLength();
        trusted = properties.getTrustedProxies().stream().map(Network::parse).toList();
    }

    public boolean isTrustedPeer(HttpServletRequest request) { return isTrusted(literal(request.getRemoteAddr())); }

    public String resolve(HttpServletRequest request) {
        InetAddress peer = literal(request.getRemoteAddr());
        if (peer == null) return "unknown";
        if (!isTrusted(peer)) return peer.getHostAddress();
        String headerName = switch (properties.getClientIpHeader()) {
            case X_FORWARDED_FOR -> "X-Forwarded-For";
            case FORWARDED -> "Forwarded";
            case X_REAL_IP -> "X-Real-IP";
        };
        String header = singleHeader(request, headerName);
        if (header == null || header.length() > 2048) return peer.getHostAddress();
        String[] elements = header.split(",", -1);
        if (elements.length > 16 || (properties.getClientIpHeader() == TrustedProxyProperties.ClientIpHeader.X_REAL_IP && elements.length != 1)) return peer.getHostAddress();
        List<InetAddress> hops = new ArrayList<>();
        for (String element : elements) {
            String address = properties.getClientIpHeader() == TrustedProxyProperties.ClientIpHeader.FORWARDED
                    ? forwardedFor(element) : element.strip();
            InetAddress hop = literal(address);
            if (hop == null) return peer.getHostAddress();
            hops.add(hop);
        }
        // Walk from the socket towards the client. Stop at the first untrusted hop.
        InetAddress client = peer;
        for (int index = hops.size() - 1; index >= 0 && isTrusted(client); index--) client = hops.get(index);
        return client.getHostAddress();
    }

    public String rateLimitAddress(HttpServletRequest request) {
        Object resolved = request.getAttribute(CLIENT_IP);
        InetAddress address = literal(resolved instanceof String value ? value : resolve(request));
        if (address == null) return "unknown";
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            for (int bit = ipv6Prefix; bit < 128; bit++) bytes[bit / 8] &= (byte) ~(1 << (7 - bit % 8));
        }
        return HexFormat.of().formatHex(bytes);
    }

    public static String singleHeader(HttpServletRequest request, String name) {
        var values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) return null;
        String value = values.nextElement();
        return values.hasMoreElements() ? null : value;
    }

    private String forwardedFor(String element) {
        String found = null;
        for (String parameter : element.split(";", -1)) {
            int equals = parameter.indexOf('=');
            if (equals <= 0) return null;
            if (!parameter.substring(0, equals).strip().equalsIgnoreCase("for")) continue;
            if (found != null) return null;
            String value = parameter.substring(equals + 1).strip();
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 2) value = value.substring(1, value.length() - 1);
            if (value.startsWith("[")) {
                int close = value.indexOf(']');
                if (close < 0 || !validPortSuffix(value.substring(close + 1))) return null;
                value = value.substring(1, close);
            } else if (value.indexOf(':') > 0 && value.indexOf(':') == value.lastIndexOf(':') && value.contains(".")) {
                if (!validPortSuffix(value.substring(value.indexOf(':')))) return null;
                value = value.substring(0, value.indexOf(':'));
            }
            found = value;
        }
        return found;
    }

    private boolean validPortSuffix(String suffix) {
        if (suffix.isEmpty()) return true;
        if (!suffix.matches(":[0-9]{1,5}")) return false;
        int port = Integer.parseInt(suffix.substring(1));
        return port > 0 && port <= 65535;
    }

    private boolean isTrusted(InetAddress address) { return address != null && trusted.stream().anyMatch(network -> network.contains(address)); }

    static InetAddress literal(String value) {
        if (value == null || value.isBlank() || value.length() > 45) return null;
        if (value.indexOf(':') >= 0) {
            if (!value.matches("[0-9a-fA-F:.]+")) return null;
            try { return InetAddress.getByName(value); }
            catch (UnknownHostException ignored) { return null; }
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] bytes = new byte[4];
        for (int index = 0; index < parts.length; index++) {
            if (!parts[index].matches("0|[1-9][0-9]{0,2}")) return null;
            int part = Integer.parseInt(parts[index]);
            if (part > 255) return null;
            bytes[index] = (byte) part;
        }
        try { return InetAddress.getByAddress(bytes); }
        catch (UnknownHostException impossible) { throw new IllegalStateException("Invalid IP bytes"); }
    }

    private record Network(byte[] address, int prefix) {
        static Network parse(String cidr) {
            String[] parts = cidr.strip().split("/", -1);
            InetAddress parsed = literal(parts[0]);
            if (parsed == null || parts.length > 2) throw new IllegalArgumentException("Trusted proxies must be literal IPs or CIDRs");
            int prefix;
            try { prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : parsed.getAddress().length * 8; }
            catch (NumberFormatException failure) { throw new IllegalArgumentException("Invalid trusted proxy CIDR"); }
            if (prefix < 1 || prefix > parsed.getAddress().length * 8) throw new IllegalArgumentException("Invalid trusted proxy prefix");
            return new Network(parsed.getAddress(), prefix);
        }
        boolean contains(InetAddress candidate) {
            byte[] bytes = candidate.getAddress();
            if (bytes.length != address.length) return false;
            for (int bit = 0; bit < prefix; bit++) {
                int mask = 1 << (7 - bit % 8);
                if ((bytes[bit / 8] & mask) != (address[bit / 8] & mask)) return false;
            }
            return true;
        }
    }
}
