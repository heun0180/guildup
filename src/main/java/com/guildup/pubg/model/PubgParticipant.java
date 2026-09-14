package com.guildup.pubg.model;

/** 한 PUBG 경기 참가자의 안정적인 식별 정보다. */
public record PubgParticipant(String accountId, String name, int kills) {
    /** 기존 활동 조회 테스트와 호출부의 호환성을 유지한다. */
    public PubgParticipant(String accountId, String name) {
        this(accountId, name, 0);
    }
}
