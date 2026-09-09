package com.guildup.community.dto;

/** 팀 만들기 화면과 결과에서 사용하는 클랜원 정보다. */
public record TeamMakerParticipantResponse(
        Long memberId,
        String discordNickname,
        String pubgNickname,
        Double damageDealt,
        Long roundsPlayed,
        Double averageDamage
) {
    public static TeamMakerParticipantResponse selectable(Long memberId, String discordNickname, String pubgNickname) {
        return new TeamMakerParticipantResponse(memberId, discordNickname, pubgNickname, null, null, null);
    }
}
