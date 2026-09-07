package com.guildup.community.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommunityGameActivityRuleTests {

    private final Community community = new Community("치즈 클랜");
    private final CommunityGame game = new CommunityGame(community, GameType.BATTLEGROUNDS_KAKAO);

    @Test
    void defaultsToCheeseClanRule() {
        CommunityGameActivityRule rule = CommunityGameActivityRule.defaultRule(game);

        assertThat(rule.getActivityPeriodDays()).isEqualTo(14);
        assertThat(rule.getMinimumClanMembersInRoster()).isEqualTo(2);
    }

    @Test
    void countsTheCurrentMemberAsAClanMemberInTheSameTeam() {
        CommunityGameActivityRule rule = CommunityGameActivityRule.defaultRule(game);

        assertThat(rule.isSatisfiedByClanMemberCountInRoster(1)).isFalse();
        assertThat(rule.isSatisfiedByClanMemberCountInRoster(2)).isTrue();
        assertThat(rule.isSatisfiedByClanMemberCountInRoster(3)).isTrue();
    }

    @Test
    void rejectsInvalidPeriodsAndUnsupportedMemberCounts() {
        assertThatThrownBy(() -> new CommunityGameActivityRule(game, 0, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1일 이상");
        assertThatThrownBy(() -> new CommunityGameActivityRule(game, 366, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("365일 이하");
        assertThatThrownBy(() -> new CommunityGameActivityRule(game, 14, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2명, 3명, 4명");
        assertThatThrownBy(() -> new CommunityGameActivityRule(game, 14, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2명, 3명, 4명");
    }
}
