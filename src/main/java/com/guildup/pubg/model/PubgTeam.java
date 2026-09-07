package com.guildup.pubg.model;

import java.util.List;

/** PUBG Match 응답의 한 팀과 참가자 목록이다. */
public record PubgTeam(List<PubgParticipant> participants) {
    public PubgTeam {
        participants = participants == null ? List.of() : List.copyOf(participants);
    }
}
