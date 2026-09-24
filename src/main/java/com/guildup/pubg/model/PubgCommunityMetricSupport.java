package com.guildup.pubg.model;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 커뮤니티와 무관한 PUBG 원본 관계를 콘텐츠별 metric으로 투영한다. */
public final class PubgCommunityMetricSupport {
    private static final String VEHICLE_PASSENGER_PREFIX = "__VEHICLE_PASSENGER__:";

    private PubgCommunityMetricSupport() {}

    public static String vehiclePassenger(int rideSequence, String accountId) {
        return VEHICLE_PASSENGER_PREFIX + rideSequence + ":" + accountId;
    }

    public static Map<String, BigDecimal> forCommunity(Map<String, BigDecimal> source,
                                                        Set<String> communityAccounts) {
        Map<String, BigDecimal> result = new LinkedHashMap<>(source);
        Map<String, Integer> passengersByRide = new LinkedHashMap<>();
        result.keySet().removeIf(key -> {
            if (!key.startsWith(VEHICLE_PASSENGER_PREFIX)) return false;
            int accountSeparator = key.indexOf(':', VEHICLE_PASSENGER_PREFIX.length());
            if (accountSeparator < 0) return true;
            String ride = key.substring(0, accountSeparator);
            String accountId = key.substring(accountSeparator + 1);
            if (communityAccounts.contains(accountId)) passengersByRide.merge(ride, 1, Integer::sum);
            return true;
        });
        int maximum = passengersByRide.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        result.merge("MAX_CLAN_VEHICLE_PASSENGERS", BigDecimal.valueOf(maximum), BigDecimal::max);
        return Map.copyOf(result);
    }
}
