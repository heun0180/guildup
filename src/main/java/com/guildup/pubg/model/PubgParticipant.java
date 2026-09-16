package com.guildup.pubg.model;

/** Match 응답에서 안정적으로 제공되는 참가자별 통계다. */
public record PubgParticipant(
        String accountId, String name, int kills, double damageDealt, int assists,
        int dbnos, int headshotKills, int heals, int boosts, int revives, int roadKills,
        int winPlace, double survivalTime, double walkDistance, double rideDistance,
        double swimDistance, double longestKill
) {
    /** 기존 활동 조회 테스트와 호출부의 호환성을 유지한다. */
    public PubgParticipant(String accountId, String name) {
        this(accountId, name, 0);
    }
    public PubgParticipant(String accountId, String name, int kills) {
        this(accountId, name, kills, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
