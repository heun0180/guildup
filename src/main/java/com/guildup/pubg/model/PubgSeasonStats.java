package com.guildup.pubg.model;

/** 한 플레이어가 한 시즌의 전체 일반 게임 모드에서 기록한 딜량과 판수다. */
public record PubgSeasonStats(String accountId, double damageDealt, long roundsPlayed) {
    public static PubgSeasonStats empty(String accountId) {
        return new PubgSeasonStats(accountId, 0, 0);
    }
}
