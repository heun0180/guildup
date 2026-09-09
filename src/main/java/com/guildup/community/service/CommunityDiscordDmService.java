package com.guildup.community.service;

import com.guildup.community.domain.DiscordCommunityConnection;
import com.guildup.discord.dto.DiscordDmFailureReason;
import com.guildup.discord.dto.DiscordDmRequest;
import com.guildup.discord.dto.DiscordDmRecipientsResponse;
import com.guildup.discord.dto.DiscordDmResponse;
import com.guildup.discord.dto.DiscordDmResult;
import com.guildup.discord.dto.DiscordMemberResponse;
import com.guildup.discord.exception.DuplicateDiscordDmRequestException;
import com.guildup.discord.service.DiscordDirectMessageClient;
import com.guildup.discord.service.DiscordGuildService;
import com.guildup.discord.service.DiscordMemberService;
import com.guildup.discord.service.DiscordRoleService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 커뮤니티 운영자의 Discord 멤버 조회와 안전한 순차 DM 발송을 조율한다. */
@Service
public class CommunityDiscordDmService {

    static final int DISCORD_MESSAGE_MAX_LENGTH = 2_000;
    private static final Logger log = LoggerFactory.getLogger(CommunityDiscordDmService.class);

    private final CommunityAccessService accessService;
    private final DiscordCommunityConnectionService connectionService;
    private final DiscordGuildService guildService;
    private final DiscordMemberService memberService;
    private final DiscordRoleService roleService;
    private final DiscordDirectMessageClient directMessageClient;
    private final int maxRecipients;
    private final Duration duplicateWindow;
    private final Clock clock;
    private final ConcurrentHashMap<String, Instant> recentRequests = new ConcurrentHashMap<>();

    @Autowired
    public CommunityDiscordDmService(
            CommunityAccessService accessService,
            DiscordCommunityConnectionService connectionService,
            DiscordGuildService guildService,
            DiscordMemberService memberService,
            DiscordRoleService roleService,
            DiscordDirectMessageClient directMessageClient,
            @Value("${discord.dm.max-recipients:50}") int maxRecipients,
            @Value("${discord.dm.duplicate-window:10s}") Duration duplicateWindow
    ) {
        this(accessService, connectionService, guildService, memberService, roleService,
                directMessageClient, maxRecipients, duplicateWindow, Clock.systemUTC());
    }

    CommunityDiscordDmService(
            CommunityAccessService accessService,
            DiscordCommunityConnectionService connectionService,
            DiscordGuildService guildService,
            DiscordMemberService memberService,
            DiscordRoleService roleService,
            DiscordDirectMessageClient directMessageClient,
            int maxRecipients,
            Duration duplicateWindow,
            Clock clock
    ) {
        this.accessService = accessService;
        this.connectionService = connectionService;
        this.guildService = guildService;
        this.memberService = memberService;
        this.roleService = roleService;
        this.directMessageClient = directMessageClient;
        this.maxRecipients = maxRecipients;
        this.duplicateWindow = duplicateWindow;
        this.clock = clock;
    }

    /** 관리 권한과 Discord 연결을 확인한 뒤 봇을 제외한 전체 수신자 후보를 반환한다. */
    public DiscordDmRecipientsResponse getRecipients(Long userId, Long communityId) {
        accessService.requireManagementAccess(userId, communityId);
        DiscordCommunityConnection connection = connectionService.getRequiredConnection(communityId);
        return new DiscordDmRecipientsResponse(
                maxRecipients,
                roleService.getMembers(connection.getDiscordGuildId())
        );
    }

    /** 요청을 검증하고 실제 서버 멤버로 확인된 사용자에게 한 명씩 DM을 발송한다. */
    public DiscordDmResponse send(Long userId, Long communityId, DiscordDmRequest request) {
        accessService.requireManagementAccess(userId, communityId);
        ValidatedRequest validated = validate(request);
        DiscordCommunityConnection connection = connectionService.getRequiredConnection(communityId);
        Guild guild = guildService.getGuildById(connection.getDiscordGuildId());
        registerRequest(communityId, validated);

        List<DiscordDmResult> results = new ArrayList<>();
        for (String discordUserId : validated.discordUserIds()) {
            Member member = memberService.findHumanMember(guild, discordUserId).orElse(null);
            if (member == null) {
                results.add(DiscordDmResult.failure(
                        discordUserId, null, DiscordDmFailureReason.USER_NOT_FOUND));
                continue;
            }

            String displayName = memberService.getDisplayName(member);
            try {
                directMessageClient.send(discordUserId, validated.message());
                results.add(DiscordDmResult.success(discordUserId, displayName));
            } catch (RuntimeException exception) {
                DiscordDmFailureReason reason = directMessageClient.isDmNotAvailable(exception)
                        ? DiscordDmFailureReason.DM_NOT_AVAILABLE
                        : DiscordDmFailureReason.DISCORD_API_ERROR;
                results.add(DiscordDmResult.failure(discordUserId, displayName, reason));
                log.warn("Discord DM delivery failed - communityId: {}, guildId: {}, userId: {}, reason: {}",
                        communityId, connection.getDiscordGuildId(), discordUserId, reason);
            }
        }

        DiscordDmResponse response = DiscordDmResponse.from(List.copyOf(results));
        log.info("Discord DM request completed - communityId: {}, guildId: {}, total: {}, success: {}, failed: {}, messageLength: {}",
                communityId, connection.getDiscordGuildId(), response.total(), response.success(),
                response.failed(), validated.message().length());
        return response;
    }

    private ValidatedRequest validate(DiscordDmRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw badRequest("Discord DM message must not be blank");
        }
        if (request.message().length() > DISCORD_MESSAGE_MAX_LENGTH) {
            throw badRequest("Discord DM message must be 2000 characters or fewer");
        }
        if (request.discordUserIds() == null) {
            throw badRequest("At least one Discord recipient is required");
        }

        LinkedHashSet<String> uniqueIds = new LinkedHashSet<>();
        for (String userId : request.discordUserIds()) {
            if (userId != null && !userId.isBlank()) {
                uniqueIds.add(userId.trim());
            }
        }
        if (uniqueIds.isEmpty()) {
            throw badRequest("At least one Discord recipient is required");
        }
        if (uniqueIds.size() > maxRecipients) {
            throw badRequest("Discord DM recipient limit exceeded: " + maxRecipients);
        }
        return new ValidatedRequest(List.copyOf(uniqueIds), request.message());
    }

    private void registerRequest(Long communityId, ValidatedRequest request) {
        Instant now = clock.instant();
        Instant expiresBefore = now.minus(duplicateWindow);
        recentRequests.entrySet().removeIf(entry -> entry.getValue().isBefore(expiresBefore));

        String fingerprint = fingerprint(communityId, request);
        AtomicBoolean duplicate = new AtomicBoolean(false);
        recentRequests.compute(fingerprint, (ignored, previous) -> {
            if (previous != null && previous.isAfter(expiresBefore)) {
                duplicate.set(true);
                return previous;
            }
            return now;
        });
        if (duplicate.get()) {
            throw new DuplicateDiscordDmRequestException();
        }
    }

    private String fingerprint(Long communityId, ValidatedRequest request) {
        List<String> sortedIds = request.discordUserIds().stream().sorted().toList();
        String value = communityId + "\n" + String.join(",", sortedIds) + "\n" + request.message();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private record ValidatedRequest(List<String> discordUserIds, String message) {
    }
}
