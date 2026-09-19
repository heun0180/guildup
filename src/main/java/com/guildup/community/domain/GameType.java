package com.guildup.community.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** 커뮤니티가 활동하는 게임 종류다. */
public enum GameType {
    BATTLEGROUNDS_KAKAO("배틀그라운드 카카오", pubgCapabilities()),
    BATTLEGROUNDS_STEAM("배틀그라운드 스팀", pubgCapabilities());

    private final String displayName;
    private final Set<GameCapability> capabilities;

    GameType(String displayName, Set<GameCapability> capabilities) {
        this.displayName = displayName;
        this.capabilities = Collections.unmodifiableSet(EnumSet.copyOf(capabilities));
    }

    public String getDisplayName() {
        return displayName;
    }

    public Set<GameCapability> getCapabilities() {
        return capabilities;
    }

    public boolean supports(GameCapability capability) {
        return capabilities.contains(capability);
    }

    private static Set<GameCapability> pubgCapabilities() {
        return EnumSet.allOf(GameCapability.class);
    }
}
