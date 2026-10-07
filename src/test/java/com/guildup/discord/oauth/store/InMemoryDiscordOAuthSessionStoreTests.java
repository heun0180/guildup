package com.guildup.discord.oauth.store;

import com.guildup.discord.oauth.dto.DiscordManageableGuildResponse;
import com.guildup.discord.oauth.dto.DiscordOAuthResultResponse;
import com.guildup.discord.oauth.dto.DiscordOAuthUserResponse;
import com.guildup.discord.oauth.exception.InvalidDiscordGuildSelectionException;
import com.guildup.discord.oauth.exception.InvalidDiscordOAuthStateException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryDiscordOAuthSessionStoreTests {

    private final InMemoryDiscordOAuthSessionStore store = new InMemoryDiscordOAuthSessionStore();

    @Test void withdrawalPurgesDiscordProfileCopiesAcrossCommunitiesAndCreationResults() {
        var withdrawn = new DiscordOAuthResultResponse(new DiscordOAuthUserResponse("10", "private", "private", null), List.of());
        var retained = new DiscordOAuthResultResponse(new DiscordOAuthUserResponse("20", "other", "other", null), List.of());
        var first = store.saveResult(1L, withdrawn); var creation = store.saveResult(null, withdrawn);
        var other = store.saveResult(2L, retained);
        store.discardResultsForDiscordUser("10");
        assertThatThrownBy(() -> store.getResult(1L, first)).isInstanceOf(com.guildup.discord.oauth.exception.DiscordOAuthResultNotFoundException.class);
        assertThatThrownBy(() -> store.getResult(null, creation)).isInstanceOf(com.guildup.discord.oauth.exception.DiscordOAuthResultNotFoundException.class);
        assertThat(store.getResult(2L, other)).isSameAs(retained);
    }

    @Test
    void createsOpaqueStateAndResolvesCommunityOnlyOnce() {
        String state = store.createState(1L);

        assertThat(state).isNotBlank().isNotEqualTo("1");
        assertThat(store.consumeState(state)).isEqualTo(1L);
        assertThatThrownBy(() -> store.consumeState(state))
                .isInstanceOf(InvalidDiscordOAuthStateException.class);
    }

    @Test
    void rejectsUnknownState() {
        assertThatThrownBy(() -> store.consumeState("unknown"))
                .isInstanceOf(InvalidDiscordOAuthStateException.class);
    }

    @Test
    void onlyAllowsGuildContainedInOAuthResult() {
        DiscordManageableGuildResponse guild =
                new DiscordManageableGuildResponse("100", "치즈 클랜", null, true);
        DiscordOAuthResultResponse result = new DiscordOAuthResultResponse(
                new DiscordOAuthUserResponse("10", "apple", "애플", null),
                List.of(guild)
        );
        String resultId = store.saveResult(1L, result);

        assertThat(store.getResult(1L, resultId)).isSameAs(result);
        assertThat(store.getSelectedGuild(1L, resultId, "100")).isEqualTo(guild);
        assertThat(store.getResult(1L, resultId)).isSameAs(result);
        assertThatThrownBy(() -> store.consumeSelectedGuild(1L, resultId, "999"))
                .isInstanceOf(InvalidDiscordGuildSelectionException.class);
        assertThat(store.consumeSelectedGuild(1L, resultId, "100")).isEqualTo(guild);
    }
}
