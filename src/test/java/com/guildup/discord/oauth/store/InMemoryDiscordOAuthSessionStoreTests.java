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
        assertThatThrownBy(() -> store.consumeSelectedGuild(1L, resultId, "999"))
                .isInstanceOf(InvalidDiscordGuildSelectionException.class);
        assertThat(store.consumeSelectedGuild(1L, resultId, "100")).isEqualTo(guild);
    }
}
