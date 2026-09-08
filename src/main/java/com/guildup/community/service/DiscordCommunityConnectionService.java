package com.guildup.community.service;

import com.guildup.community.domain.Community;
import com.guildup.community.domain.DiscordCommunityConnection;
import com.guildup.community.exception.CommunityNotFoundException;
import com.guildup.community.exception.DiscordGuildAlreadyConnectedException;
import com.guildup.community.exception.DiscordCommunityConnectionNotFoundException;
import com.guildup.community.exception.DiscordCommunityConnectionConflictException;
import com.guildup.community.repository.CommunityRepository;
import com.guildup.community.repository.DiscordCommunityConnectionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** GuildUp 커뮤니티와 Discord 서버 간 1:1 연결의 조회와 생성을 담당한다. */
@Service
public class DiscordCommunityConnectionService {

    private final CommunityRepository communityRepository;
    private final DiscordCommunityConnectionRepository connectionRepository;

    public DiscordCommunityConnectionService(
            CommunityRepository communityRepository,
            DiscordCommunityConnectionRepository connectionRepository
    ) {
        this.communityRepository = communityRepository;
        this.connectionRepository = connectionRepository;
    }

    /** 커뮤니티의 Discord 연결을 반환한다. */
    @Transactional
    public DiscordCommunityConnection getRequiredConnection(Long communityId) {
        if (!communityRepository.existsById(communityId)) {
            throw new CommunityNotFoundException(communityId);
        }

        return connectionRepository.findByCommunityId(communityId)
                .orElseThrow(() -> new DiscordCommunityConnectionNotFoundException(communityId));
    }

    /**
     * 커뮤니티와 Discord 서버를 연결한다.
     * 양쪽 모두 하나의 상대만 갖도록 연결 데이터를 기준으로 중복을 검사한다.
     */
    @Transactional
    public DiscordCommunityConnection connect(Long communityId, String discordGuildId, String discordGuildName) {
        Community community = communityRepository.findById(communityId)
                .orElseThrow(() -> new CommunityNotFoundException(communityId));
        String normalizedGuildId = requireGuildId(discordGuildId);

        // 동일한 Discord 서버가 다른 커뮤니티에 연결되어 있는지 확인한다.
        boolean connectedToAnotherCommunity = connectionRepository.findByDiscordGuildId(normalizedGuildId)
                .filter(connection -> !connection.getCommunity().getId().equals(communityId))
                .isPresent();
        if (connectedToAnotherCommunity) {
            throw new DiscordGuildAlreadyConnectedException(normalizedGuildId);
        }

        // 기존 연결이 있다면 같은 서버로의 갱신만 허용하고, 없다면 새 연결을 만든다.
        DiscordCommunityConnection connection = connectionRepository.findByCommunityId(communityId)
                .map(existingConnection -> requireSameGuild(
                        existingConnection,
                        normalizedGuildId,
                        communityId
                ))
                .orElseGet(() -> new DiscordCommunityConnection(community, normalizedGuildId, discordGuildName));
        connection.updateGuild(normalizedGuildId, discordGuildName);
        try {
            // flush까지 이 메서드 안에서 수행해 DB UNIQUE 위반을 비즈니스 예외로 변환한다.
            return connectionRepository.saveAndFlush(connection);
        } catch (DataIntegrityViolationException exception) {
            throw translateConstraintViolation(exception, normalizedGuildId, communityId);
        }
    }

    private RuntimeException translateConstraintViolation(
            DataIntegrityViolationException exception,
            String discordGuildId,
            Long communityId
    ) {
        String detail = rootMessage(exception).toLowerCase();
        if (detail.contains("community_id") || detail.contains("uk_discord_connection_community")) {
            return new DiscordCommunityConnectionConflictException(communityId);
        }
        return new DiscordGuildAlreadyConnectedException(discordGuildId);
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? "" : current.getMessage();
    }

    /** 기존 커뮤니티 연결을 다른 Discord 서버로 바꾸는 것을 막는다. */
    private DiscordCommunityConnection requireSameGuild(
            DiscordCommunityConnection connection,
            String discordGuildId,
            Long communityId
    ) {
        if (!connection.getDiscordGuildId().equals(discordGuildId)) {
            throw new DiscordCommunityConnectionConflictException(communityId);
        }
        return connection;
    }

    /** Discord 서버 ID가 비어 있지 않도록 확인하고 앞뒤 공백을 제거한다. */
    private String requireGuildId(String discordGuildId) {
        if (discordGuildId == null || discordGuildId.isBlank()) {
            throw new IllegalArgumentException("Discord guild ID must not be blank");
        }

        return discordGuildId.trim();
    }
}
