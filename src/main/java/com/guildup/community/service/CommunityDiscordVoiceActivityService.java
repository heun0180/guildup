package com.guildup.community.service;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.domain.CommunityMember;
import com.guildup.community.domain.CommunityMemberAccount;
import com.guildup.community.domain.CommunityMemberStatus;
import com.guildup.community.domain.DiscordCommunityConnection;
import com.guildup.community.repository.CommunityMemberAccountRepository;
import com.guildup.community.repository.CommunityMemberRepository;
import com.guildup.discord.domain.DiscordVoiceSession;
import com.guildup.discord.dto.DiscordVoiceActivityDetailResponse;
import com.guildup.discord.dto.DiscordVoiceActivitySummaryResponse;
import com.guildup.discord.dto.DiscordVoiceSessionResponse;
import com.guildup.discord.repository.DiscordVoiceSessionRepository;
import com.guildup.discord.service.DiscordGuildService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 저장된 Discord 세션을 현재 CommunityMember 계정 구조에 결합해 조회한다. */
@Service
@Transactional(readOnly = true)
public class CommunityDiscordVoiceActivityService {

    private static final int DEFAULT_PERIOD_DAYS = 14;

    private final CommunityAccessService accessService;
    private final DiscordCommunityConnectionService connectionService;
    private final CommunityMemberRepository memberRepository;
    private final CommunityMemberAccountRepository accountRepository;
    private final DiscordVoiceSessionRepository sessionRepository;
    private final DiscordGuildService guildService;
    private final Clock clock;

    public CommunityDiscordVoiceActivityService(
            CommunityAccessService accessService,
            DiscordCommunityConnectionService connectionService,
            CommunityMemberRepository memberRepository,
            CommunityMemberAccountRepository accountRepository,
            DiscordVoiceSessionRepository sessionRepository,
            DiscordGuildService guildService,
            Clock clock
    ) {
        this.accessService = accessService;
        this.connectionService = connectionService;
        this.memberRepository = memberRepository;
        this.accountRepository = accountRepository;
        this.sessionRepository = sessionRepository;
        this.guildService = guildService;
        this.clock = clock;
    }

    public List<DiscordVoiceActivitySummaryResponse> getSummaries(Long userId, Long communityId) {
        accessService.requireManagementAccess(userId, communityId);
        connectionService.getRequiredConnection(communityId);

        List<CommunityMember> members = memberRepository.findByCommunityIdAndStatusOrderByIdAsc(
                communityId, CommunityMemberStatus.ACTIVE
        );
        Map<Long, CommunityMemberAccount> accounts = discordAccountsByMember(communityId);
        List<String> discordUserIds = accounts.values().stream()
                .map(CommunityMemberAccount::getExternalUserId)
                .distinct()
                .toList();
        Instant periodEnd = clock.instant();
        Instant periodStart = periodEnd.minus(DEFAULT_PERIOD_DAYS, ChronoUnit.DAYS);
        Map<String, List<DiscordVoiceSession>> sessionsByUser = loadSessions(
                communityId, discordUserIds, periodStart, periodEnd
        ).stream().collect(Collectors.groupingBy(DiscordVoiceSession::getDiscordUserId));

        return members.stream()
                .map(member -> toSummary(
                        member, accounts.get(member.getId()), sessionsByUser, periodStart, periodEnd
                ))
                .sorted(Comparator
                        .comparing(DiscordVoiceActivitySummaryResponse::currentlyConnected).reversed()
                        .thenComparing(DiscordVoiceActivitySummaryResponse::totalSeconds, Comparator.reverseOrder())
                        .thenComparing(DiscordVoiceActivitySummaryResponse::nickname))
                .toList();
    }

    public DiscordVoiceActivityDetailResponse getDetail(
            Long userId,
            Long communityId,
            Long communityMemberId
    ) {
        accessService.requireManagementAccess(userId, communityId);
        DiscordCommunityConnection connection = connectionService.getRequiredConnection(communityId);
        CommunityMember member = memberRepository.findById(communityMemberId)
                .filter(candidate -> candidate.getCommunity().getId().equals(communityId))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "클랜원을 찾을 수 없습니다."
                ));
        CommunityMemberAccount account = discordAccountsByMember(communityId).get(communityMemberId);
        Instant periodEnd = clock.instant();
        Instant periodStart = periodEnd.minus(DEFAULT_PERIOD_DAYS, ChronoUnit.DAYS);
        List<DiscordVoiceSession> sessions = account == null
                ? List.of()
                : loadSessions(
                        communityId, List.of(account.getExternalUserId()), periodStart, periodEnd
                );
        Guild guild = guildService.findGuildById(connection.getDiscordGuildId()).orElse(null);
        List<DiscordVoiceSessionResponse> responses = sessions.stream()
                .map(session -> new DiscordVoiceSessionResponse(
                        session.getDiscordChannelId(),
                        resolveChannelName(guild, session),
                        session.getJoinedAt(),
                        session.getLeftAt(),
                        durationSeconds(session, periodStart, periodEnd),
                        session.isOpen()
                ))
                .toList();
        long totalSeconds = responses.stream().mapToLong(DiscordVoiceSessionResponse::durationSeconds).sum();
        return new DiscordVoiceActivityDetailResponse(
                member.getId(), member.getNickname(),
                account == null ? null : account.getExternalUserId(),
                totalSeconds, responses
        );
    }

    private DiscordVoiceActivitySummaryResponse toSummary(
            CommunityMember member,
            CommunityMemberAccount account,
            Map<String, List<DiscordVoiceSession>> sessionsByUser,
            Instant periodStart,
            Instant periodEnd
    ) {
        if (account == null) {
            return new DiscordVoiceActivitySummaryResponse(
                    member.getId(), member.getNickname(), null, 0, null, false
            );
        }
        List<DiscordVoiceSession> sessions = sessionsByUser.getOrDefault(
                account.getExternalUserId(), List.of()
        );
        long total = sessions.stream()
                .mapToLong(session -> durationSeconds(session, periodStart, periodEnd))
                .sum();
        Instant lastJoinedAt = sessions.stream()
                .map(DiscordVoiceSession::getJoinedAt)
                .max(Comparator.naturalOrder())
                .orElse(null);
        boolean connected = sessions.stream().anyMatch(DiscordVoiceSession::isOpen);
        return new DiscordVoiceActivitySummaryResponse(
                member.getId(), member.getNickname(), account.getExternalUserId(),
                total, lastJoinedAt, connected
        );
    }

    private List<DiscordVoiceSession> loadSessions(
            Long communityId,
            List<String> discordUserIds,
            Instant periodStart,
            Instant periodEnd
    ) {
        if (discordUserIds.isEmpty()) return List.of();
        return sessionRepository.findOverlappingSessions(
                communityId, discordUserIds, periodStart, periodEnd
        );
    }

    private long durationSeconds(DiscordVoiceSession session, Instant periodStart, Instant periodEnd) {
        Instant start = session.getJoinedAt().isBefore(periodStart) ? periodStart : session.getJoinedAt();
        Instant rawEnd = session.getLeftAt() == null ? periodEnd : session.getLeftAt();
        Instant end = rawEnd.isAfter(periodEnd) ? periodEnd : rawEnd;
        return end.isAfter(start) ? Duration.between(start, end).getSeconds() : 0;
    }

    private String resolveChannelName(Guild guild, DiscordVoiceSession session) {
        GuildChannel currentChannel = guild == null
                ? null
                : guild.getGuildChannelById(session.getDiscordChannelId());
        if (currentChannel != null) return currentChannel.getName();
        if (session.getChannelNameSnapshot() != null && !session.getChannelNameSnapshot().isBlank()) {
            return session.getChannelNameSnapshot();
        }
        return "알 수 없는 채널";
    }

    private Map<Long, CommunityMemberAccount> discordAccountsByMember(Long communityId) {
        return accountRepository.findByCommunityIdAndProvider(
                communityId, ExternalAccountProvider.DISCORD
        ).stream().collect(Collectors.toMap(
                account -> account.getCommunityMember().getId(),
                Function.identity(),
                (first, ignored) -> first,
                LinkedHashMap::new
        ));
    }
}
