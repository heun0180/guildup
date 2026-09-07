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
                .containsExactlyInAnyOrder("id", "community", "nickname", "status", "createdAt", "updatedAt")
                .doesNotContain("discordUserId");
    }

    @Test
    void battlegroundGameTypesOwnTheirDisplayNamesAndPubgShards() {
        assertThat(GameType.BATTLEGROUNDS_KAKAO.getDisplayName()).isEqualTo("배틀그라운드 카카오");
        assertThat(GameType.BATTLEGROUNDS_KAKAO.getPubgShard()).isEqualTo("kakao");
        assertThat(GameType.BATTLEGROUNDS_STEAM.getDisplayName()).isEqualTo("배틀그라운드 스팀");
        assertThat(GameType.BATTLEGROUNDS_STEAM.getPubgShard()).isEqualTo("steam");
    }
}
