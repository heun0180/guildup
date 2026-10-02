package com.guildup.pubg.model;

import java.util.Objects;

/** 서로 다른 플랫폼의 동일 Match ID도 독립적인 원본으로 취급한다. */
public record PubgMatchKey(PubgPlatform platform, String matchId) {
    public PubgMatchKey {
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(matchId, "matchId");
    }
}
