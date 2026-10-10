package com.guildup.user.reset;

import org.springframework.stereotype.Component;
import java.time.Clock;
import java.util.*;

/** Independent of login/verification counters. Bounded, synchronized sliding windows; HMAC IP keys only. */
@Component
public class PasswordResetRequestLimiter {
    private final Map<String, Deque<Long>> entries = new HashMap<>();
    private final PasswordResetProperties properties;
    private final Clock clock;
    private long lastCleanup;
    public PasswordResetRequestLimiter(PasswordResetProperties properties, Clock clock) { this.properties = properties; this.clock = clock; }
    public synchronized boolean reserve(String ipHash, boolean tokenRequest) {
        long now = clock.millis(), window = tokenRequest ? 60000 : 86400000;
        if (now - lastCleanup >= 60000) {
            entries.entrySet().removeIf(e -> e.getValue().isEmpty()
                    || e.getValue().getLast() <= now - (e.getKey().startsWith("token:") ? 60000 : 86400000));
            lastCleanup = now;
        }
        String key = (tokenRequest ? "token:" : "mail:") + ipHash;
        var requests = entries.get(key);
        if (requests == null) {
            if (entries.size() >= properties.maxIpEntries()) return false;
            requests = new ArrayDeque<>(); entries.put(key, requests);
        }
        while (!requests.isEmpty() && requests.getFirst() <= now - window) requests.removeFirst();
        int limit = tokenRequest ? properties.tokenRequestsPerMinute() : properties.ipDailyLimit();
        if (requests.size() >= limit || (!tokenRequest
                && requests.stream().filter(at -> at > now - 3600000).count() >= properties.ipHourlyLimit())) return false;
        requests.addLast(now); return true;
    }
}
