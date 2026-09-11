package com.guildup.discord.service;

import com.guildup.discord.exception.DiscordResourceNotFoundException;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/** JDA 캐시를 통해 봇이 참여한 Discord 서버를 조회한다. */
@Service
public class DiscordGuildService {

    private final JDA jda;

    public DiscordGuildService(JDA jda) {
        this.jda = jda;
    }

    /** 봇이 현재 참여 중인 모든 Discord 서버를 반환한다. */
    public List<Guild> getGuilds() {
        return jda.getGuilds();
    }

    /** 서버 ID로 Discord 서버를 찾고 없으면 404 예외를 발생시킨다. */
    public Guild getGuildById(String guildId) {
        return findGuildById(guildId)
                .orElseThrow(() -> new DiscordResourceNotFoundException(
                        "Discord guild not found: " + guildId
                ));
    }

    /** 잘못된 형식의 ID까지 포함해 서버 조회 실패를 Optional.empty로 통일한다. */
    public Optional<Guild> findGuildById(String guildId) {
        try {
            return Optional.ofNullable(jda.getGuildById(guildId));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

}
