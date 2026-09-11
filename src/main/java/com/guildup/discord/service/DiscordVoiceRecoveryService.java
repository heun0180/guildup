package com.guildup.discord.service;

import com.guildup.community.repository.DiscordCommunityConnectionRepository;
import com.guildup.discord.domain.DiscordVoiceSession;
import com.guildup.discord.repository.DiscordVoiceSessionRepository;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** JDA 준비 직후 현재 음성 상태와 DB의 열린 세션을 맞춘다. */
@Service
public class DiscordVoiceRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(DiscordVoiceRecoveryService.class);

    private final DiscordCommunityConnectionRepository connectionRepository;
    private final DiscordVoiceSessionRepository sessionRepository;
    private final DiscordVoiceSessionService sessionService;
    private final Clock clock;

    public DiscordVoiceRecoveryService(
            DiscordCommunityConnectionRepository connectionRepository,
            DiscordVoiceSessionRepository sessionRepository,
            DiscordVoiceSessionService sessionService,
            Clock clock
    ) {
        this.connectionRepository = connectionRepository;
        this.sessionRepository = sessionRepository;
        this.sessionService = sessionService;
        this.clock = clock;
    }

    public void reconcile(JDA jda) {
        Instant recoveredAt = clock.instant();
        connectionRepository.findAllWithCommunity().forEach(connection -> {
            Guild guild = jda.getGuildById(connection.getDiscordGuildId());
            if (guild == null) return;

            Map<String, GuildVoiceState> currentStates = guild.getVoiceStates().stream()
                    .filter(state -> state.inAudioChannel() && !state.getMember().getUser().isBot())
                    .collect(Collectors.toMap(
                            state -> state.getMember().getId(),
                            Function.identity(),
                            (first, ignored) -> first
                    ));

            // 종료 시각은 다운타임 중 알 수 없으므로 재시작 시각으로 닫아 무기한 열린 행을 방지한다.
            for (DiscordVoiceSession open : sessionRepository
                    .findByCommunityIdAndLeftAtIsNull(connection.getCommunity().getId())) {
                if (!currentStates.containsKey(open.getDiscordUserId())) {
                    sessionService.handleVoiceUpdate(
                            guild.getId(), open.getDiscordUserId(), null, null, recoveredAt
                    );
                }
            }

            // 열린 행이 없거나 채널이 달라졌다면 재시작 시각부터 현재 채널 세션을 연다.
            currentStates.values().forEach(state -> sessionService.handleVoiceUpdate(
                    guild.getId(),
                    state.getMember().getId(),
                    state.getChannel().getId(),
                    state.getChannel().getName(),
                    recoveredAt
            ));
        });
        log.info("Discord voice sessions reconciled at {}", recoveredAt);
    }
}
