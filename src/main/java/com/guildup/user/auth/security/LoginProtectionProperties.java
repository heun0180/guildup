package com.guildup.user.auth.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "security.login")
public class LoginProtectionProperties {
    private int accountFreeFailures = 4;
    private Duration accountBaseDelay = Duration.ofSeconds(2);
    private Duration accountMaxDelay = Duration.ofSeconds(30);
    private Duration accountFailureTtl = Duration.ofMinutes(15);
    private int ipShortLimit = 120;
    private Duration ipShortWindow = Duration.ofMinutes(1);
    private int ipLongLimit = 600;
    private Duration ipLongWindow = Duration.ofMinutes(15);
    private int sprayAccounts = 20;
    private int sprayFailures = 20;
    private int sprayFailurePercent = 80;
    private Duration sprayWindow = Duration.ofMinutes(5);
    private Duration sprayDelay = Duration.ofSeconds(30);
    private int maxAccounts = 20000;
    private int maxIps = 10000;
    private int maxConcurrentVerifications = 8;
    private int ipv6PrefixLength = 64;
    private Duration eventInterval = Duration.ofMinutes(1);
    private String hmacSecret = "";

    public void validate() {
        if (accountFreeFailures < 1 || accountFreeFailures > 100 || ipShortLimit < 1 || ipLongLimit < ipShortLimit
                || sprayAccounts < 2 || sprayAccounts > 100 || sprayFailures < 2
                || sprayFailurePercent < 1 || sprayFailurePercent > 100
                || maxAccounts < 1 || maxAccounts > 100000 || maxIps < 1 || maxIps > 50000
                || maxConcurrentVerifications < 1 || maxConcurrentVerifications > 64
                || ipv6PrefixLength < 48 || ipv6PrefixLength > 128
                || !positive(accountBaseDelay) || !positive(accountMaxDelay) || !positive(accountFailureTtl)
                || !positive(ipShortWindow) || !positive(ipLongWindow) || !positive(sprayWindow)
                || !positive(sprayDelay) || !positive(eventInterval)
                || accountMaxDelay.compareTo(Duration.ofMinutes(1)) > 0
                || accountBaseDelay.compareTo(accountMaxDelay) > 0
                || accountFailureTtl.compareTo(accountMaxDelay) < 0
                || ipLongWindow.compareTo(ipShortWindow) < 0
                || ipLongWindow.compareTo(Duration.ofHours(1)) > 0
                || sprayWindow.compareTo(Duration.ofHours(1)) > 0
                || accountFailureTtl.compareTo(Duration.ofHours(1)) > 0
                || sprayDelay.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("Invalid login protection policy");
        }
    }

    private boolean positive(Duration value) { return value != null && value.toMillis() >= 1; }
    public int getAccountFreeFailures() { return accountFreeFailures; }
    public void setAccountFreeFailures(int value) { accountFreeFailures = value; }
    public Duration getAccountBaseDelay() { return accountBaseDelay; }
    public void setAccountBaseDelay(Duration value) { accountBaseDelay = value; }
    public Duration getAccountMaxDelay() { return accountMaxDelay; }
    public void setAccountMaxDelay(Duration value) { accountMaxDelay = value; }
    public Duration getAccountFailureTtl() { return accountFailureTtl; }
    public void setAccountFailureTtl(Duration value) { accountFailureTtl = value; }
    public int getIpShortLimit() { return ipShortLimit; }
    public void setIpShortLimit(int value) { ipShortLimit = value; }
    public Duration getIpShortWindow() { return ipShortWindow; }
    public void setIpShortWindow(Duration value) { ipShortWindow = value; }
    public int getIpLongLimit() { return ipLongLimit; }
    public void setIpLongLimit(int value) { ipLongLimit = value; }
    public Duration getIpLongWindow() { return ipLongWindow; }
    public void setIpLongWindow(Duration value) { ipLongWindow = value; }
    public int getSprayAccounts() { return sprayAccounts; }
    public void setSprayAccounts(int value) { sprayAccounts = value; }
    public int getSprayFailures() { return sprayFailures; }
    public void setSprayFailures(int value) { sprayFailures = value; }
    public int getSprayFailurePercent() { return sprayFailurePercent; }
    public void setSprayFailurePercent(int value) { sprayFailurePercent = value; }
    public Duration getSprayWindow() { return sprayWindow; }
    public void setSprayWindow(Duration value) { sprayWindow = value; }
    public Duration getSprayDelay() { return sprayDelay; }
    public void setSprayDelay(Duration value) { sprayDelay = value; }
    public int getMaxAccounts() { return maxAccounts; }
    public void setMaxAccounts(int value) { maxAccounts = value; }
    public int getMaxIps() { return maxIps; }
    public void setMaxIps(int value) { maxIps = value; }
    public int getMaxConcurrentVerifications() { return maxConcurrentVerifications; }
    public void setMaxConcurrentVerifications(int value) { maxConcurrentVerifications = value; }
    public int getIpv6PrefixLength() { return ipv6PrefixLength; }
    public void setIpv6PrefixLength(int value) { ipv6PrefixLength = value; }
    public Duration getEventInterval() { return eventInterval; }
    public void setEventInterval(Duration value) { eventInterval = value; }
    public String getHmacSecret() { return hmacSecret; }
    public void setHmacSecret(String value) { hmacSecret = value; }
}
