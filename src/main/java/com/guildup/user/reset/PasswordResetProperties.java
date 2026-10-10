package com.guildup.user.reset;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("auth.password-reset")
public record PasswordResetProperties(
        @DefaultValue("false") boolean mailEnabled,
        @DefaultValue("https://guild-up.com/password-reset.html") URI url,
        @DefaultValue("30m") Duration tokenTtl,
        @DefaultValue("60s") Duration requestInterval,
        @DefaultValue("3") int accountHourlyLimit,
        @DefaultValue("5") int accountDailyLimit,
        @DefaultValue("20") int ipHourlyLimit,
        @DefaultValue("50") int ipDailyLimit,
        @DefaultValue("20") int tokenRequestsPerMinute,
        @DefaultValue("10000") int maxIpEntries,
        @DefaultValue("30") int maxEventsPerMinute,
        @DefaultValue("30") int mailDailyBudget,
        @DefaultValue("600") int mailMonthlyBudget,
        @DefaultValue("7d") Duration retention,
        @DefaultValue("false") boolean allowLocalHttp) {
    public PasswordResetProperties {
        boolean local = allowLocalHttp && url != null && "http".equalsIgnoreCase(url.getScheme())
                && java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(url.getHost());
        if (url == null || (!"https".equalsIgnoreCase(url.getScheme()) && !local) || url.getHost() == null
                || url.getRawUserInfo() != null || url.getRawQuery() != null || url.getRawFragment() != null
                || !"/password-reset.html".equals(url.getPath()))
            throw new IllegalArgumentException("Password reset URL must be a trusted HTTPS /password-reset.html URL (explicit local loopback HTTP allowed), without credentials, query or fragment");
        if (tokenTtl.compareTo(Duration.ofMinutes(1)) < 0 || tokenTtl.compareTo(Duration.ofHours(24)) > 0
                || requestInterval.compareTo(Duration.ofSeconds(1)) < 0 || requestInterval.compareTo(Duration.ofDays(1)) > 0
                || retention.compareTo(Duration.ofDays(2)) < 0)
            throw new IllegalArgumentException("Invalid password reset durations");
        for (int limit : new int[]{accountHourlyLimit, accountDailyLimit, ipHourlyLimit, ipDailyLimit,
                tokenRequestsPerMinute, maxIpEntries, maxEventsPerMinute, mailDailyBudget, mailMonthlyBudget})
            if (limit < 1 || limit > 100000) throw new IllegalArgumentException("Invalid password reset limit");
    }
}
