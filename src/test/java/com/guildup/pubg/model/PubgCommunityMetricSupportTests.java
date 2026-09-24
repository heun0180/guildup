package com.guildup.pubg.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PubgCommunityMetricSupportTests {
    @Test void derivesVehiclePassengersForEachCommunityWithoutPersistingACommunitySpecificCount() {
        Map<String, BigDecimal> stored = new LinkedHashMap<>();
        stored.put(PubgCommunityMetricSupport.vehiclePassenger(1, "account-a"), BigDecimal.ONE);
        stored.put(PubgCommunityMetricSupport.vehiclePassenger(1, "account-b"), BigDecimal.ONE);
        stored.put(PubgCommunityMetricSupport.vehiclePassenger(2, "account-a"), BigDecimal.ONE);
        stored.put("KILLS", BigDecimal.TEN);

        Map<String, BigDecimal> firstCommunity = PubgCommunityMetricSupport.forCommunity(
                stored, Set.of("account-a", "account-b"));
        Map<String, BigDecimal> secondCommunity = PubgCommunityMetricSupport.forCommunity(
                stored, Set.of("account-a"));

        assertThat(firstCommunity.get("MAX_CLAN_VEHICLE_PASSENGERS")).isEqualByComparingTo("2");
        assertThat(secondCommunity.get("MAX_CLAN_VEHICLE_PASSENGERS")).isEqualByComparingTo("1");
        assertThat(firstCommunity.get("KILLS")).isEqualByComparingTo("10");
        assertThat(firstCommunity.keySet()).noneMatch(key -> key.startsWith("__VEHICLE_PASSENGER__:"));
    }
}
