package com.guildup.pubg.model;

import java.util.List;

/** GuildUp 활동 조회에 필요한 PUBG 선수 정보만 남긴 내부 모델이다. */
public record PubgPlayer(String accountId, String name, List<String> matchIds) {
    public PubgPlayer {
        matchIds = matchIds == null ? List.of() : List.copyOf(matchIds);
    }
}
