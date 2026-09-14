package com.guildup.feedback.service;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.domain.CommunityUser;
import com.guildup.community.exception.CommunityNotFoundException;
import com.guildup.community.repository.CommunityRepository;
import com.guildup.community.service.CommunityAccessService;
import com.guildup.feedback.dto.FeedbackRequest;
import com.guildup.feedback.dto.FeedbackResponse;
import com.guildup.user.domain.UserExternalAccount;
import com.guildup.user.repository.UserExternalAccountRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Service
@Transactional(readOnly = true)
public class FeedbackService {
    private static final int TITLE_MAX_LENGTH = 100;
    private static final int CONTENT_MAX_LENGTH = 3000;
    private static final String NOT_CONNECTED = "연결 안 됨";
    private static final ZoneId MAIL_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter RECEIVED_AT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CommunityRepository communities;
    private final CommunityAccessService access;
    private final UserExternalAccountRepository externalAccounts;
    private final FeedbackMailService mailService;
    private final Clock clock;

    public FeedbackService(CommunityRepository communities,
                           CommunityAccessService access,
                           UserExternalAccountRepository externalAccounts,
                           FeedbackMailService mailService,
                           Clock clock) {
        this.communities = communities;
        this.access = access;
        this.externalAccounts = externalAccounts;
        this.mailService = mailService;
        this.clock = clock;
    }

    public FeedbackResponse send(Long userId, Long communityId, FeedbackRequest request) {
        validate(request);
        communities.findById(communityId).orElseThrow(() -> new CommunityNotFoundException(communityId));
        CommunityUser membership = access.requireCommunityMember(userId, communityId);
        UserExternalAccount discord = externalAccounts
                .findByUserIdAndProvider(userId, ExternalAccountProvider.DISCORD)
                .orElse(null);

        String title = request.title().trim();
        String content = request.content().trim();
        String typeName = request.type().getDisplayName();
        String communityName = membership.getCommunity().getName();
        String subject = "[GuildUp][%s][%s] %s".formatted(
                headerValue(typeName), headerValue(communityName), headerValue(title));
        String receivedAt = RECEIVED_AT_FORMAT.format(clock.instant().atZone(MAIL_ZONE));
        String discordNickname = discord == null || isBlank(discord.getExternalUsername())
                ? NOT_CONNECTED : discord.getExternalUsername();
        String discordUserId = discord == null || isBlank(discord.getExternalUserId())
                ? NOT_CONNECTED : discord.getExternalUserId();

        String body = """
                GuildUp 사용자 문의가 접수되었습니다.

                ================================
                문의 정보
                ================================

                문의 유형: %s

                커뮤니티: %s
                Community ID: %d

                사용자: %s
                GuildUp User ID: %d
                커뮤니티 권한: %s

                Discord 닉네임: %s
                Discord User ID: %s

                접수 시간: %s

                ================================
                제목
                ================================

                %s

                ================================
                내용
                ================================

                %s
                """.formatted(typeName, communityName, communityId,
                membership.getUser().getNickname(), userId, membership.getRole(),
                discordNickname, discordUserId, receivedAt, title, content);

        mailService.send(new FeedbackMailMessage(subject, body));
        return new FeedbackResponse("소중한 의견 감사합니다. 개발자에게 전달되었습니다.");
    }

    private void validate(FeedbackRequest request) {
        if (request == null || request.type() == null) {
            throw badRequest("문의 유형을 선택해 주세요.");
        }
        if (isBlank(request.title())) {
            throw badRequest("제목을 입력해 주세요.");
        }
        if (request.title().length() > TITLE_MAX_LENGTH) {
            throw badRequest("제목은 100자 이하로 입력해 주세요.");
        }
        if (request.title().indexOf('\r') >= 0 || request.title().indexOf('\n') >= 0) {
            throw badRequest("제목에는 줄바꿈을 사용할 수 없습니다.");
        }
        if (isBlank(request.content())) {
            throw badRequest("내용을 입력해 주세요.");
        }
        if (request.content().length() > CONTENT_MAX_LENGTH) {
            throw badRequest("내용은 3000자 이하로 입력해 주세요.");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String headerValue(String value) {
        return value.replace('\r', ' ').replace('\n', ' ');
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
