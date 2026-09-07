package com.guildup.community.activity;

import com.guildup.community.domain.Community;
import com.guildup.community.domain.CommunityGame;
import com.guildup.community.domain.CommunityGameActivityRule;
import com.guildup.community.domain.GameType;
import com.guildup.pubg.model.PubgMatch;
import com.guildup.pubg.model.PubgParticipant;
import com.guildup.pubg.model.PubgPlayer;
import com.guildup.pubg.model.PubgTeam;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CommunityActivityEvaluatorTests {

    private final CommunityActivityEvaluator evaluator = new CommunityActivityEvaluator();
    private final CommunityGame game = new CommunityGame(
            new Community("치즈 클랜"), GameType.BATTLEGROUNDS_KAKAO
    );
    private final Map<String, ClanMemberIdentity> clan = Map.of(
            "account.A", new ClanMemberIdentity(1L, "애플/93/sa-gwa"),
            "account.B", new ClanMemberIdentity(2L, "절미/95/jul-mi")
    );
    private final Instant now = Instant.parse("2026-09-07T00:00:00Z");

    @Test
    void recognizesActivityWhenSelfAndOneClanMemberAreOnTheSameTeam() {
        CommunityGameActivityRule rule = new CommunityGameActivityRule(game, 14, 2);
        PubgMatch match = match("recent", now.minusSeconds(3600), team("account.A", "account.B"));

        var result = evaluate(rule, new PubgPlayer("account.A", "sa-gwa", List.of("recent")), match);

        assertThat(result.status()).isEqualTo(ClanActivityStatus.ACTIVE);
        assertThat(result.matches().getFirst().clanMemberCountInTeam()).isEqualTo(2);
        assertThat(result.matches().getFirst().clanMembersInTeam()).containsExactly("절미/95/jul-mi");
        assertThat(result.matches().getFirst().activityRecognized()).isTrue();
    }

    @Test
    void doesNotRecognizeASoloClanMemberOrAClanMemberOnAnotherTeam() {
        CommunityGameActivityRule rule = new CommunityGameActivityRule(game, 14, 2);
        PubgMatch match = match(
                "opponents", now.minusSeconds(3600),
                team("account.A", "external.X"), team("account.B", "external.Y")
        );

        var result = evaluate(rule, new PubgPlayer("account.A", "sa-gwa", List.of("opponents")), match);

        assertThat(result.status()).isEqualTo(ClanActivityStatus.NO_CLAN_ACTIVITY);
        assertThat(result.matches().getFirst().clanMemberCountInTeam()).isEqualTo(1);
        assertThat(result.matches().getFirst().activityRecognized()).isFalse();
    }

    @Test
    void usesConfiguredMinimumMemberCountInsteadOfTwo() {
        CommunityGameActivityRule rule = new CommunityGameActivityRule(game, 14, 3);
        PubgMatch match = match("two-members", now.minusSeconds(3600), team("account.A", "account.B"));

        var result = evaluate(rule, new PubgPlayer("account.A", "sa-gwa", List.of("two-members")), match);

        assertThat(result.status()).isEqualTo(ClanActivityStatus.NO_CLAN_ACTIVITY);
    }

    @Test
    void ignoresRecognizedMatchesOutsideTheConfiguredPeriod() {
        CommunityGameActivityRule rule = new CommunityGameActivityRule(game, 7, 2);
        PubgMatch old = match("old", now.minusSeconds(8 * 86_400), team("account.A", "account.B"));

        var result = evaluator.evaluate(
                new PubgPlayer("account.A", "sa-gwa", List.of("old")),
                rule, now.minusSeconds(7 * 86_400), clan, Map.of("old", old)
        );

        assertThat(result.status()).isEqualTo(ClanActivityStatus.NO_RECENT_MATCHES);
        assertThat(result.matches()).isEmpty();
    }

    @Test
    void reportsNoRecentMatchesWhenPlayerHasNoMatches() {
        CommunityGameActivityRule rule = new CommunityGameActivityRule(game, 14, 2);

        var result = evaluator.evaluate(
                new PubgPlayer("account.A", "sa-gwa", List.of()),
                rule, now.minusSeconds(14 * 86_400), clan, Map.of()
        );

        assertThat(result.status()).isEqualTo(ClanActivityStatus.NO_RECENT_MATCHES);
    }

    private ClanMemberActivityEvaluation evaluate(
            CommunityGameActivityRule rule,
            PubgPlayer player,
            PubgMatch match
    ) {
        return evaluator.evaluate(
                player, rule, now.minusSeconds(14 * 86_400), clan,
                Map.of(match.matchId(), match)
        );
    }

    private PubgMatch match(String id, Instant playedAt, PubgTeam... teams) {
        return new PubgMatch(id, playedAt, "squad", List.of(teams));
    }

    private PubgTeam team(String... accountIds) {
        return new PubgTeam(java.util.Arrays.stream(accountIds)
                .map(accountId -> new PubgParticipant(accountId, accountId))
                .toList());
    }
}
