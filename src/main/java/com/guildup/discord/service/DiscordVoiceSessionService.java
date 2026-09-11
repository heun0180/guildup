package com.guildup.discord.service;

import com.guildup.community.domain.DiscordCommunityConnection;
import com.guildup.community.repository.DiscordCommunityConnectionRepository;
import com.guildup.discord.domain.DiscordVoiceSession;
import com.guildup.discord.repository.DiscordVoiceSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/** Discord 음성 상태 변화를 열린 세션 하나로 정규화한다. */
@Service
public class DiscordVoiceSessionService {

    private final DiscordCommunityConnectionRepository connectionRepository;
    private final DiscordVoiceSessionRepository sessionRepository;

    public DiscordVoiceSessionService(
            DiscordCommunityConnectionRepository connectionRepository,
            DiscordVoiceSessionRepository sessionRepository
    ) {
        this.connectionRepository = connectionRepository;
        this.sessionRepository = sessionRepository;
    }

    @Transactional
    public void handleVoiceUpdate(
            String guildId,
            String userId,
            String joinedChannelId,
            String joinedChannelName,
            Instant eventTime
    ) {
        Optional<DiscordCommunityConnection> connection = connectionRepository.findByDiscordGuildId(guildId);
        if (connection.isEmpty()) return;

        Long communityId = connection.get().getCommunity().getId();
        DiscordVoiceSession openSession = sessionRepository
                .findByCommunityIdAndDiscordUserIdAndLeftAtIsNull(communityId, userId)
                .orElse(null);

        // 같은 채널 입장 이벤트가 중복 전달되면 현재 열린 세션을 그대로 유지한다.
        if (openSession != null && joinedChannelId != null
                && openSession.getDiscordChannelId().equals(joinedChannelId)) {
            return;
        }

        // 퇴장과 채널 이동은 기존 세션을 먼저 닫는다.
        if (openSession != null) {
            openSession.close(eventTime);
            sessionRepository.save(openSession);
            // 이동 시 INSERT보다 종료 UPDATE가 먼저 DB에 반영되어 열린 세션 UNIQUE를 비운다.
            sessionRepository.flush();
        }

        if (joinedChannelId != null) {
            sessionRepository.saveAndFlush(new DiscordVoiceSession(
                    connection.get().getCommunity(), guildId, userId,
                    joinedChannelId, joinedChannelName, eventTime
            ));
        }
    }
}
