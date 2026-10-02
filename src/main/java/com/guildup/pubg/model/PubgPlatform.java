package com.guildup.pubg.model;

/** PUBG 계정과 경기의 출처. 기존 GameType 및 API shard 계약은 유지한다. */
public enum PubgPlatform {
    KAKAO("kakao"),
    STEAM("steam");

    private final String shard;

    PubgPlatform(String shard) { this.shard = shard; }

    public String getShard() { return shard; }

    public static PubgPlatform fromShard(String shard) {
        for (PubgPlatform platform : values()) {
            if (platform.shard.equals(shard)) return platform;
        }
        throw new IllegalArgumentException("지원하지 않는 PUBG shard: " + shard);
    }
}
