package com.guildup.user.verification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("auth.email-verification")
public record EmailVerificationProperties(
        @DefaultValue("true") boolean mailEnabled,
        @DefaultValue("false") boolean enforceNewUsers,
        @DefaultValue("https://guildup.example/email-verification.html") URI url,
        @DefaultValue("300s") Duration tokenTtl,
        @DefaultValue("60s") Duration resendInterval,
        @DefaultValue("3") int accountHourlyLimit,
        @DefaultValue("5") int accountDailyLimit,
        @DefaultValue("20") int ipHourlyLimit,
        @DefaultValue("50") int ipDailyLimit,
        @DefaultValue("20") int confirmPerMinute,
        @DefaultValue("10000") int maxIpEntries,
        @DefaultValue("30") int maxEventsPerMinute,
        @DefaultValue("7d") Duration retention,
        @DefaultValue("10m") Duration deliveryTimeout,
        @DefaultValue("false") boolean allowLocalHttp) {
    public EmailVerificationProperties {
        boolean localHttp = allowLocalHttp && url != null && "http".equalsIgnoreCase(url.getScheme()) && url.getHost() != null
                && java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(url.getHost().toLowerCase(java.util.Locale.ROOT));
        if (url == null || (!"https".equalsIgnoreCase(url.getScheme()) && !localHttp) || url.getHost() == null
                || url.getRawUserInfo() != null || url.getRawQuery() != null || url.getRawFragment() != null)
            throw new IllegalArgumentException("Email verification URL must use HTTPS, or an explicitly enabled local loopback HTTP URL, without credentials, query or fragment");
        if (tokenTtl.isNegative() || tokenTtl.isZero() || tokenTtl.compareTo(Duration.ofDays(7)) > 0
                || resendInterval.compareTo(Duration.ofSeconds(1)) < 0 || resendInterval.compareTo(Duration.ofDays(1)) > 0
                || retention.compareTo(Duration.ofDays(2)) < 0 || deliveryTimeout.compareTo(Duration.ofMinutes(1)) < 0)
            throw new IllegalArgumentException("Invalid email verification durations");
        for (int limit : new int[]{accountHourlyLimit, accountDailyLimit, ipHourlyLimit, ipDailyLimit,
                confirmPerMinute, maxIpEntries, maxEventsPerMinute})
            if (limit < 1 || limit > 100000) throw new IllegalArgumentException("Invalid email verification limit");
    }
}
