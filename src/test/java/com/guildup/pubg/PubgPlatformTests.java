package com.guildup.pubg;

import com.guildup.community.domain.GameType;
import com.guildup.pubg.model.PubgPlatform;
import com.guildup.pubg.support.PubgGameSupport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class PubgPlatformTests {
    @Test void existingGameTypesKeepTheirApiShards() {
        assertThat(PubgGameSupport.requirePlatform(GameType.BATTLEGROUNDS_KAKAO)).isEqualTo(PubgPlatform.KAKAO);
        assertThat(PubgGameSupport.requireShard(GameType.BATTLEGROUNDS_KAKAO)).isEqualTo("kakao");
        assertThat(PubgGameSupport.requirePlatform(GameType.BATTLEGROUNDS_STEAM)).isEqualTo(PubgPlatform.STEAM);
        assertThat(PubgGameSupport.requireShard(GameType.BATTLEGROUNDS_STEAM)).isEqualTo("steam");
    }
    @Test void unknownShardCannotBeSilentlyConvertedToKakao() {
        assertThatThrownBy(() -> PubgPlatform.fromShard("unknown")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PubgPlatform.fromShard(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
