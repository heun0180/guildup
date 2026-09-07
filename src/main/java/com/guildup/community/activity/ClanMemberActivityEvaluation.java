package com.guildup.community.activity;

import java.time.Instant;
import java.util.List;

/** 한 클랜원의 현재 상태와 기간 내 경기별 판정 결과다. */
public record ClanMemberActivityEvaluation(
        ClanActivityStatus status,
        Instant lastClanActivityAt,
        List<ClanActivityMatch> matches
) {
    public ClanMemberActivityEvaluation {
        matches = List.copyOf(matches);
    }
}
