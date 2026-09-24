package com.guildup.pubg;

import com.guildup.discord.bot.DiscordBot;
import com.guildup.pubg.model.*;
import com.guildup.pubg.repository.*;
import com.guildup.pubg.service.PubgMatchFactWriter;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pubg-persistence;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class PubgMatchPersistenceTests {
    @Autowired PubgMatchFactWriter writer;
    @Autowired PubgStoredMatchRepository matches;
    @Autowired PubgStoredMatchPlayerRepository players;
    @Autowired PubgStoredMatchKillRepository kills;
    @MockitoBean JDA jda;
    @MockitoBean DiscordBot bot;

    @BeforeEach void clean() { kills.deleteAll(); players.deleteAll(); matches.deleteAll(); }

    @Test void concurrentCollectorsCannotDuplicateAMatchOrPlayerFact() throws Exception {
        PubgMatch match = new PubgMatch("same-match", Instant.parse("2026-09-25T00:00:00Z"), "squad",
                "Erangel_Main", "official", false, null,
                List.of(new PubgTeam(List.of(new PubgParticipant("account-a", "A", 3)))));
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<?>> futures = List.of(
                    executor.submit(() -> saveAtTheSameTime(barrier, match)),
                    executor.submit(() -> saveAtTheSameTime(barrier, match)));
            for (Future<?> future : futures) future.get();
        }

        assertThat(matches.count()).isEqualTo(1);
        assertThat(players.count()).isEqualTo(1);
    }

    @Test void firstCollectorStoresEveryMatchParticipantForLaterAccountReuse() {
        PubgMatch match = new PubgMatch("shared-match", Instant.parse("2026-09-25T00:00:00Z"), "squad",
                "Erangel_Main", "official", false, null,
                List.of(new PubgTeam(List.of(
                        new PubgParticipant("account-a", "A", 3),
                        new PubgParticipant("account-b", "B", 1)))));

        writer.saveMatchIfAbsent("kakao", match);

        assertThat(players.findAll()).extracting(value -> value.getAccountId())
                .containsExactlyInAnyOrder("account-a", "account-b");
    }

    private void saveAtTheSameTime(CyclicBarrier barrier, PubgMatch match) {
        try {
            barrier.await();
            writer.saveMatchIfAbsent("kakao", match);
        } catch (DataIntegrityViolationException ignored) {
            // The database unique key is the final arbiter for a true race.
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        } catch (BrokenBarrierException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
