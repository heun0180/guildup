package com.guildup.discord.dto;

import java.util.List;

/** 여러 수신자의 개별 결과와 성공/실패 합계를 반환한다. */
public record DiscordDmResponse(
        int total,
        int success,
        int failed,
        List<DiscordDmResult> results
) {
    public static DiscordDmResponse from(List<DiscordDmResult> results) {
        int succeeded = (int) results.stream().filter(DiscordDmResult::success).count();
        return new DiscordDmResponse(results.size(), succeeded, results.size() - succeeded, results);
    }
}
