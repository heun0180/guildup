package com.guildup.community.domain;

/** 커뮤니티가 활동하는 게임 종류다. */
public enum GameType {
    BATTLEGROUNDS_KAKAO("배틀그라운드 카카오", "kakao", true),
    BATTLEGROUNDS_STEAM("배틀그라운드 스팀", "steam", true);

    private final String displayName;
    private final String pubgShard;
    private final boolean rosterActivityRuleSupported;

    GameType(String displayName, String pubgShard, boolean rosterActivityRuleSupported) {
        this.displayName = displayName;
        this.pubgShard = pubgShard;
        this.rosterActivityRuleSupported = rosterActivityRuleSupported;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** 추후 PUBG API 요청에서 사용할 플랫폼 shard다. */
    public String getPubgShard() {
        return pubgShard;
    }

    /** 같은 팀의 클랜원 수를 기준으로 활동을 판정할 수 있는 게임인지 나타낸다. */
    public boolean supportsRosterActivityRule() {
        return rosterActivityRuleSupported;
    }
}
