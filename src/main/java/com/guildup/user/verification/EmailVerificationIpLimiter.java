package com.guildup.user.verification;

import org.springframework.stereotype.Component;
import java.time.Clock;
import java.util.*;

/** 로그인 실패 카운터와 독립적이다. IP는 기존 신뢰 프록시 판별 후 HMAC으로만 보관한다. */
@Component
public class EmailVerificationIpLimiter {
    private final Map<String, Deque<Long>> entries = new HashMap<>();
    private final EmailVerificationProperties properties;
    private final Clock clock;
    private long lastCleanup;
    public EmailVerificationIpLimiter(EmailVerificationProperties properties, Clock clock) { this.properties = properties; this.clock = clock; }
    public synchronized long reserve(String ipHash, boolean confirm) {
        long now = clock.millis(), window = confirm ? 60000 : 86400000;
        if (now - lastCleanup >= 60000) {
            entries.entrySet().removeIf(entry -> entry.getValue().isEmpty()
                    || entry.getValue().getLast() <= now - (entry.getKey().startsWith("confirm:") ? 60000 : 86400000));
            lastCleanup = now;
        }
        String key = (confirm ? "confirm:" : "mail:") + ipHash;
        var requests = entries.get(key);
        if (requests == null) {
            if (entries.size() >= properties.maxIpEntries()) return 60;
            requests = new ArrayDeque<>(); entries.put(key, requests);
        }
        while (!requests.isEmpty() && requests.getFirst() <= now - window) requests.removeFirst();
        long retry = 0;
        int limit = confirm ? properties.confirmPerMinute() : properties.ipDailyLimit();
        if (requests.size() >= limit) retry = requests.getFirst() + window - now;
        if (!confirm) {
            var hourly = requests.stream().filter(at -> at > now - 3600000).toList();
            if (hourly.size() >= properties.ipHourlyLimit()) retry = Math.max(retry, hourly.getFirst() + 3600000 - now);
        }
        if (retry > 0) return (long) Math.ceil(retry / 1000.0);
        requests.addLast(now);
        return 0;
    }
}
