package com.guildup.community.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class CommunityDomainTests {

    @Test
    void communityDoesNotContainExternalServiceFields() {
        assertThat(Arrays.stream(Community.class.getDeclaredFields())
                .map(field -> field.getName()))
                .containsExactlyInAnyOrder("id", "name", "createdAt")
                .doesNotContain("discordGuildId", "discordMemberRoleId");
    }

    @Test
    void communityMemberDoesNotContainDiscordUserId() {
        assertThat(Arrays.stream(CommunityMember.class.getDeclaredFields())
                .map(field -> field.getName()))
                .containsExactlyInAnyOrder("id", "community", "nickname")
                .doesNotContain("discordUserId");
    }
}
