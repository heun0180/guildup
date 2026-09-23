package com.guildup.pubg.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PubgMatchCategoryTests {
    @Test
    void classifiesOfficialAndCompetitiveMatches() {
        assertThat(category("official", false)).isEqualTo(PubgMatchCategory.NORMAL);
        assertThat(category("competitive", false)).isEqualTo(PubgMatchCategory.RANKED);
    }

    @Test
    void classifiesCasualCustomAndSpecialMatches() {
        assertThat(category("airoyale", false)).isEqualTo(PubgMatchCategory.CASUAL);
        assertThat(category("official", true)).isEqualTo(PubgMatchCategory.CUSTOM);
        assertThat(category("custom", false)).isEqualTo(PubgMatchCategory.CUSTOM);
        assertThat(category("arcade", false)).isEqualTo(PubgMatchCategory.ARCADE);
        assertThat(category("event", false)).isEqualTo(PubgMatchCategory.ARCADE);
        assertThat(category("seasonal", false)).isEqualTo(PubgMatchCategory.ARCADE);
        assertThat(category("training", false)).isEqualTo(PubgMatchCategory.ARCADE);
    }

    @Test
    void unknownOrMissingMatchTypeIsNotPromotedToNormal() {
        assertThat(category("new-mode", false)).isEqualTo(PubgMatchCategory.OTHER);
        assertThat(category(null, false)).isEqualTo(PubgMatchCategory.OTHER);
        assertThat(category(" ", false)).isEqualTo(PubgMatchCategory.OTHER);
        assertThat(category("official", null)).isEqualTo(PubgMatchCategory.OTHER);
    }

    private PubgMatchCategory category(String matchType, Boolean custom) {
        return PubgMatchCategory.from(new PubgMatch("match", Instant.EPOCH, "squad", "Erangel_Main",
                matchType, custom, null, List.of()));
    }
}
