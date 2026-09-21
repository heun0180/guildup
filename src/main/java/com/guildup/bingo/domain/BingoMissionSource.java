package com.guildup.bingo.domain;

/** 미션을 판정하는 원천이다. PUBG Match와 GuildUp 내부 결과를 섞어 처리하지 않는다. */
public enum BingoMissionSource {
    PUBG_MATCH,
    GUILDUP_CONTENT
}
