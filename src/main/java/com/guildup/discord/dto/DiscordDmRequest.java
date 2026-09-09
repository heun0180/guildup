package com.guildup.discord.dto;

import java.util.List;

/** Discord DM 수신자 ID와 메시지를 전달받는다. */
public record DiscordDmRequest(List<String> discordUserIds, String message) {
}
