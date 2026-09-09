package com.guildup.discord.dto;

import java.util.List;

/** DM 화면의 수신자 후보와 서버에서 적용하는 1회 발송 상한을 반환한다. */
public record DiscordDmRecipientsResponse(
        int maxRecipients,
        List<DiscordMemberResponse> members
) {
}
