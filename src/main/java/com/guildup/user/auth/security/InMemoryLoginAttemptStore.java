package com.guildup.user.auth.security;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.*;

/** Short synchronized state changes only. Database reads and bcrypt always run outside this monitor. */
@Component
public class InMemoryLoginAttemptStore implements LoginAttemptStore {
    private final LoginProtectionProperties policy;
    private final Clock clock;
    private final Map<String, Account> accounts = new HashMap<>();
    private final Map<String, Ip> ips = new HashMap<>();
    private int verifying;
    private long nextCapacityEvent;
    private long nextLoadEvent;

    public InMemoryLoginAttemptStore(LoginProtectionProperties policy, Clock clock) {
        policy.validate();
        this.policy = policy;
        this.clock = clock;
    }

    @Override public synchronized Decision begin(String accountId, String ipId) {
        long now = clock.millis();
        Ip ip = ips.get(ipId);
        if (ip == null) {
            if (ips.size() >= policy.getMaxIps()) return capacity(now);
            ip = new Ip(now);
            ips.put(ipId, ip);
        }
        ip.lastSeen = now;
        ip.shortWindow.resetIfExpired(now, policy.getIpShortWindow().toMillis());
        ip.longWindow.resetIfExpired(now, policy.getIpLongWindow().toMillis());
        ip.resetSprayIfExpired(now, policy.getSprayWindow().toMillis());
        ip.shortWindow.increment();
        ip.longWindow.increment();
        long ipUntil = ip.sprayUntil;
        if (ip.shortWindow.count > policy.getIpShortLimit()) ipUntil = Math.max(ipUntil, ip.shortWindow.start + policy.getIpShortWindow().toMillis());
        if (ip.longWindow.count > policy.getIpLongLimit()) ipUntil = Math.max(ipUntil, ip.longWindow.start + policy.getIpLongWindow().toMillis());
        if (ipUntil > now) {
            long retry = seconds(ipUntil - now);
            var signals = new ArrayList<Signal>();
            if (ip.report(Kind.RATE_LIMITED, now)) signals.add(signal(Kind.RATE_LIMITED, "IP", ip, 0, true, retry));
            if (ip.shortWindow.count > policy.getIpShortLimit() || ip.longWindow.count > policy.getIpLongLimit()) {
                if (ip.report(Kind.IP_VOLUME, now)) signals.add(signal(Kind.IP_VOLUME, "IP", ip, 0, true, retry));
            }
            return new Decision(null, retry, List.copyOf(signals));
        }
        Account account = accounts.get(accountId);
        if (account == null) {
            if (accounts.size() >= policy.getMaxAccounts()) return capacity(now);
            account = new Account(now);
            accounts.put(accountId, account);
        }
        account.lastSeen = now;
        if (account.failureExpires <= now) {
            account.failures = 0;
            account.blockedUntil = 0;
        }
        if (account.inFlight || account.blockedUntil > now) {
            long retry = account.inFlight ? 1 : seconds(account.blockedUntil - now);
            List<Signal> signals = account.report(Kind.RATE_LIMITED, now)
                    ? List.of(signal(Kind.RATE_LIMITED, "ACCOUNT", ip, account.failures, true, retry)) : List.of();
            // Rejected requests do not increment account failures or slide the cooldown/expiry.
            return new Decision(null, retry, signals);
        }
        if (verifying >= policy.getMaxConcurrentVerifications()) {
            List<Signal> signals = List.of();
            if (now >= nextLoadEvent) {
                nextLoadEvent = now + policy.getEventInterval().toMillis();
                signals = List.of(signal(Kind.SUSPECTED_ATTACK, "VERIFICATION_CAPACITY", ip, 0, true, 1));
            }
            return new Decision(null, 1, signals);
        }
        account.inFlight = true;
        ip.inFlight++;
        verifying++;
        return new Decision(new Reservation(accountId, account, ip, ip.sprayStart), 0, List.of());
    }

    @Override public synchronized List<Signal> complete(Permit permit, Outcome outcome) {
        if (!(permit instanceof Reservation reservation) || reservation.completed) return List.of();
        reservation.completed = true;
        long now = clock.millis();
        Account account = reservation.account;
        Ip ip = reservation.ip;
        account.inFlight = false;
        ip.inFlight--;
        verifying--;
        var signals = new ArrayList<Signal>();
        if (outcome == Outcome.SUCCESS) accounts.remove(reservation.accountId, account);
        else if (outcome == Outcome.INVALID_CREDENTIALS) {
            if (account.failureExpires <= now || account.failures == 0) {
                account.failures = 0;
                account.failureExpires = now + policy.getAccountFailureTtl().toMillis();
            }
            account.failures = Math.min(1000000, account.failures + 1);
            int exponent = account.failures - policy.getAccountFreeFailures() - 1;
            if (exponent >= 0) {
                long delay = Math.min(policy.getAccountMaxDelay().toMillis(),
                        policy.getAccountBaseDelay().toMillis() * (1L << Math.min(20, exponent)));
                account.blockedUntil = now + delay;
                if (account.report(Kind.REPEATED_FAILURE, now)) signals.add(signal(Kind.REPEATED_FAILURE, "ACCOUNT", ip, account.failures, false, seconds(delay)));
            }
        }
        // Do not attach a slow verification's result to a later observation window.
        ip.resetSprayIfExpired(now, policy.getSprayWindow().toMillis());
        if (outcome != Outcome.ABORTED && reservation.sprayStart == ip.sprayStart) {
            ip.completed = Math.min(1000000, ip.completed + 1);
            if (outcome == Outcome.INVALID_CREDENTIALS) {
                ip.failures = Math.min(1000000, ip.failures + 1);
                if (ip.failedAccounts.size() < policy.getSprayAccounts()) ip.failedAccounts.add(reservation.accountId);
            }
            if (ip.failedAccounts.size() >= policy.getSprayAccounts() && ip.failures >= policy.getSprayFailures()
                    && (long) ip.failures * 100 >= (long) ip.completed * policy.getSprayFailurePercent()) {
                // Only a new completed failure can start a fresh short cooldown.
                if (outcome == Outcome.INVALID_CREDENTIALS && ip.sprayUntil <= now) ip.sprayUntil = now + policy.getSprayDelay().toMillis();
                if (ip.report(Kind.MULTI_ACCOUNT, now)) signals.add(signal(Kind.MULTI_ACCOUNT, "IP", ip, 0, ip.sprayUntil > now, seconds(Math.max(0, ip.sprayUntil - now))));
            }
        }
        return List.copyOf(signals);
    }

    private Decision capacity(long now) {
        List<Signal> signals = List.of();
        if (now >= nextCapacityEvent) {
            nextCapacityEvent = now + policy.getEventInterval().toMillis();
            signals = List.of(new Signal(Kind.SUSPECTED_ATTACK, "STORE_CAPACITY", 0, 0, 0, true, 1));
        }
        // Never evict a live restriction to make room for an attacker-generated identity.
        return new Decision(null, 1, signals);
    }

    private Signal signal(Kind kind, String scope, Ip ip, int failures, boolean limited, long retry) {
        return new Signal(kind, scope, ip.longWindow.count, failures == 0 ? ip.failures : failures,
                ip.failedAccounts.size(), limited, retry);
    }

    private long seconds(long millis) { return millis <= 0 ? 0 : Math.max(1, (millis + 999) / 1000); }

    @Scheduled(fixedDelayString = "${security.login.cleanup-interval:1m}")
    public synchronized void cleanup() {
        long now = clock.millis();
        long accountTtl = policy.getAccountFailureTtl().toMillis();
        accounts.values().removeIf(value -> !value.inFlight && value.blockedUntil <= now
                && value.failureExpires <= now && now - value.lastSeen >= accountTtl);
        long ipTtl = Math.max(policy.getIpLongWindow().toMillis(), policy.getSprayWindow().toMillis());
        ips.values().removeIf(value -> value.inFlight == 0 && value.sprayUntil <= now && now - value.lastSeen >= ipTtl);
    }

    synchronized int accountSize() { return accounts.size(); }
    synchronized int ipSize() { return ips.size(); }

    private class Reports {
        private final EnumMap<Kind, Long> next = new EnumMap<>(Kind.class);
        boolean report(Kind kind, long now) {
            if (now < next.getOrDefault(kind, Long.MIN_VALUE)) return false;
            next.put(kind, now + policy.getEventInterval().toMillis());
            return true;
        }
    }
    private final class Account extends Reports {
        long lastSeen, failureExpires, blockedUntil;
        int failures;
        boolean inFlight;
        Account(long now) { lastSeen = now; }
    }
    private final class Ip extends Reports {
        long lastSeen, sprayStart, sprayUntil;
        int completed, failures, inFlight;
        final Set<String> failedAccounts = new HashSet<>();
        final Window shortWindow, longWindow;
        Ip(long now) { lastSeen = sprayStart = now; shortWindow = new Window(now); longWindow = new Window(now); }
        void resetSprayIfExpired(long now, long duration) {
            if (now - sprayStart >= duration) {
                sprayStart = now; completed = failures = 0; failedAccounts.clear();
            }
        }
    }
    private static final class Window {
        long start, count;
        Window(long now) { start = now; }
        void resetIfExpired(long now, long duration) { if (now - start >= duration) { start = now; count = 0; } }
        void increment() { if (count < Long.MAX_VALUE) count++; }
    }
    private final class Reservation implements Permit {
        final String accountId;
        final Account account;
        final Ip ip;
        final long sprayStart;
        boolean completed;
        Reservation(String accountId, Account account, Ip ip, long sprayStart) {
            this.accountId = accountId; this.account = account; this.ip = ip; this.sprayStart = sprayStart;
        }
        @Override public String toString() { return "LoginReservation[redacted]"; }
    }
}
