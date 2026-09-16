package com.guildup.bingo;

import com.guildup.bingo.mission.*;
import com.guildup.pubg.model.*;
import com.guildup.pubg.service.PubgTelemetryClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PubgBingoFactServiceTests {
    @Test
    void convertsOfficialTelemetryEventsToReusableFactsAndCachesPerMatch() throws Exception {
        PubgTelemetryClient telemetry = mock(PubgTelemetryClient.class);
        String json = """
                [
                 {"_T":"LogPlayerKill","_D":"2026-09-22T12:03:00Z","killer":{"accountId":"a"},"damageCauserName":"M416","distance":310,"isThroughPenetrableWall":true},
                 {"_T":"LogPlayerKillV2","_D":"2026-09-22T12:04:00Z","killer":{"accountId":"a"},"killerDamageInfo":{"damageCauserName":"Grenade","distance":20}},
                 {"_T":"LogPlayerRevive","_D":"2026-09-22T12:05:00Z","reviver":{"accountId":"a"}},
                 {"_T":"LogCharacterCarry","_D":"2026-09-22T12:06:00Z","character":{"accountId":"a"},"carryState":"start"},
                 {"_T":"LogPlayerUseThrowable","_D":"2026-09-22T12:07:00Z","attacker":{"accountId":"a"},"weapon":{"itemId":"SmokeBomb"}},
                 {"_T":"LogItemPickupFromCarepackage","_D":"2026-09-22T12:08:00Z","character":{"accountId":"a"}},
                 {"_T":"LogPlayerUseFlareGun","_D":"2026-09-22T12:09:00Z","attacker":{"accountId":"a"}},
                 {"_T":"LogVaultStart","_D":"2026-09-22T12:10:00Z","character":{"accountId":"a"},"isLedgeGrab":true},
                 {"_T":"LogWheelDestroy","_D":"2026-09-22T12:11:00Z","attacker":{"accountId":"a"}},
                 {"_T":"LogParachuteLanding","_D":"2026-09-22T12:12:00Z","character":{"accountId":"a"},"distance":2000}
                ]""";
        when(telemetry.get("https://telemetry-cdn.pubg.com/test.json")).thenReturn(JsonMapper.builder().build().readTree(json));
        PubgBingoFactService service = new PubgBingoFactService(telemetry, Clock.fixed(Instant.parse("2026-09-22T13:00:00Z"), ZoneOffset.UTC));
        PubgParticipant player = new PubgParticipant("a","Apple",2,1000,3,4,1,5,6,2,1,1,1200,5000,10000,500,310);
        PubgMatch match = new PubgMatch("m",Instant.parse("2026-09-22T12:00:00Z"),"squad","Erangel_Main",
                "https://telemetry-cdn.pubg.com/test.json",List.of(new PubgTeam(List.of(player,new PubgParticipant("b","Fox")))));

        PlayerMatchFacts facts = service.facts(match,Set.of("a","b")).get("a");
        service.facts(match,Set.of("a","b"));

        assertThat(facts.kills()).hasSize(2);
        assertThat(facts.metric("REVIVES")).isEqualByComparingTo("1");
        assertThat(facts.metric("CARRY")).isEqualByComparingTo("1");
        assertThat(facts.metric("CARE_PACKAGE_PICKUP")).isEqualByComparingTo("1");
        assertThat(facts.metric("FLARE_GUN_USED")).isEqualByComparingTo("1");
        assertThat(facts.metric("VAULT_COUNT")).isEqualByComparingTo("1");
        assertThat(facts.metric("LEDGE_GRAB_COUNT")).isEqualByComparingTo("1");
        assertThat(facts.metric("WHEEL_DESTROY_COUNT")).isEqualByComparingTo("1");
        assertThat(facts.metric("PARACHUTE_DISTANCE")).isEqualByComparingTo("2000");
        assertThat(facts.throwableUses()).containsEntry("SmokeBomb",1);
        assertThat(facts.clanMembersInMatch()).isEqualTo(1);
        verify(telemetry, times(1)).get(anyString());
    }
}
