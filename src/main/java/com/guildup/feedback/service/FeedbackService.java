package com.guildup.feedback.service;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.repository.CommunityUserRepository;
import com.guildup.developer.config.DeveloperAccessService;
import com.guildup.feedback.domain.Feedback;
import com.guildup.feedback.domain.FeedbackStatus;
import com.guildup.feedback.dto.*;
import com.guildup.feedback.repository.FeedbackRepository;
import com.guildup.user.domain.User;
import com.guildup.user.repository.UserExternalAccountRepository;
import com.guildup.user.repository.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Service
@Transactional(readOnly = true)
public class FeedbackService {
    private final FeedbackRepository feedbacks;
    private final UserRepository users;
    private final CommunityUserRepository memberships;
    private final UserExternalAccountRepository externalAccounts;
    private final DeveloperAccessService access;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public FeedbackService(FeedbackRepository feedbacks, UserRepository users, CommunityUserRepository memberships,
                           UserExternalAccountRepository externalAccounts, DeveloperAccessService access,
                           ApplicationEventPublisher events, Clock clock) {
        this.feedbacks = feedbacks;
        this.users = users;
        this.memberships = memberships;
        this.externalAccounts = externalAccounts;
        this.access = access;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public FeedbackResponse send(Long userId, Long communityId, FeedbackRequest request) {
        validate(request);
        User user = requireUser(userId);
        Long contextId = communityId != null ? communityId : request.communityId();
        // 선택적인 context만 확인한다. 멤버십이 없거나 만료돼도 문의는 등록된다.
        var membership = contextId == null ? null : memberships.findByCommunityIdAndUserId(contextId, userId).orElse(null);
        var community = membership == null ? null : membership.getCommunity();
        var feedback = feedbacks.saveAndFlush(new Feedback(user, request.type(), request.title().trim(),
                request.content().trim(), community == null ? null : community.getId(),
                community == null ? null : community.getName(), safePageRoute(request.pageRoute()), clock.instant()));
        var discord = externalAccounts.findByUserIdAndProvider(userId, ExternalAccountProvider.DISCORD).orElse(null);
        String communityName = community == null ? "없음" : community.getName();
        String subject = "[GuildUp][%s]%s %s".formatted(request.type().getDisplayName(),
                community == null ? "" : "[" + headerValue(communityName) + "]", headerValue(feedback.getTitle()));
        String body = """
                GuildUp 사용자 문의가 접수되었습니다.

                Feedback ID: %s
                문의 유형: %s
                커뮤니티: %s
                Community ID: %s
                사용자: %s
                GuildUp User ID: %s
                커뮤니티 권한: %s
                Discord 닉네임: %s
                Discord User ID: %s
                접수 시간: %s
                등록 당시 페이지: %s

                제목
                %s

                내용
                %s
                """.formatted(feedback.getId(), request.type().getDisplayName(), communityName,
                community == null ? "없음" : community.getId(), user.getNickname(), userId,
                membership == null ? "없음" : membership.getRole(),
                discord == null ? "연결 안 됨" : discord.getExternalUsername(),
                discord == null ? "연결 안 됨" : discord.getExternalUserId(),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(feedback.getCreatedAt().atZone(ZoneId.of("Asia/Seoul"))),
                feedback.getPageRoute() == null ? "없음" : feedback.getPageRoute(), feedback.getTitle(), feedback.getContent());
        events.publishEvent(new FeedbackMailMessage(subject, body));
        return new FeedbackResponse("소중한 의견 감사합니다. 문의가 접수되었습니다.");
    }

    public FeedbackResponses.Page mine(Long userId, int page, int size) {
        requireUser(userId);
        return page(feedbacks.findByUser_Id(userId, pagination(page, size)));
    }
    public FeedbackResponses.Detail mineDetail(Long userId, Long id) {
        requireUser(userId);
        return FeedbackResponses.Detail.from(feedbacks.findByIdAndUser_Id(id, userId).orElseThrow(this::notFound));
    }
    public FeedbackResponses.Page adminList(Long userId, int page, int size) {
        access.requireSystemAdmin(userId);
        return page(feedbacks.findAll(pagination(page, size)));
    }
    public FeedbackResponses.Detail adminDetail(Long userId, Long id) {
        access.requireSystemAdmin(userId);
        return FeedbackResponses.Detail.from(requireFeedback(id));
    }
    @Transactional
    public FeedbackResponses.Detail manage(Long userId, Long id, FeedbackManagementRequest request) {
        access.requireSystemAdmin(userId);
        var feedback = requireFeedback(id);
        if (request == null || request.status() == null || request.version() == null) throw badRequest("상태와 버전을 확인해 주세요.");
        if (feedback.getVersion() != request.version()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "문의가 변경되었습니다. 다시 열어 주세요.");
        }
        if (request.answer() != null && request.answer().length() > 3000) throw badRequest("답변은 3000자 이하로 입력해 주세요.");
        String answer = request.answer() == null || request.answer().isBlank() ? null : request.answer().strip();
        if (request.status() == FeedbackStatus.ANSWERED && answer == null) throw badRequest("답변 내용을 입력해 주세요.");
        feedback.manage(request.status(), answer, requireUser(userId), clock.instant());
        feedbacks.flush();
        return FeedbackResponses.Detail.from(feedback);
    }
    private Feedback requireFeedback(Long id) { return feedbacks.findById(id).orElseThrow(this::notFound); }
    private User requireUser(Long id) {
        return users.findById(id).filter(User::isActive).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인 후 이용해 주세요."));
    }
    private FeedbackResponses.Page page(org.springframework.data.domain.Page<Feedback> result) {
        return new FeedbackResponses.Page(result.getContent().stream().map(FeedbackResponses.Item::from).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }
    private PageRequest pagination(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw badRequest("페이지와 조회 개수를 확인해 주세요. (1~100)");
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
    }
    // 쿼리/해시/호스트/사용자정보를 저장하지 않는다. route 형태가 아니면 선택 context를 버린다.
    static String safePageRoute(String value) {
        if (value == null || value.length() > 2000) return null;
        try {
            String route = URI.create(value).getPath();
            if (route == null || route.length() > 300) return null;
            return route.matches("/|/[a-z-]+(?:\\.html)?|/help(?:/[a-z-]+)*|/developer(?:/[a-z-]+(?:/[0-9]+)?)*") ? route : null;
        } catch (IllegalArgumentException ignored) { return null; }
    }
    private void validate(FeedbackRequest r) {
        if (r == null || r.type() == null) throw badRequest("문의 유형을 선택해 주세요.");
        if (r.title() == null || r.title().isBlank()) throw badRequest("제목을 입력해 주세요.");
        if (r.title().length() > 100) throw badRequest("제목은 100자 이하로 입력해 주세요.");
        if (r.title().indexOf('\r') >= 0 || r.title().indexOf('\n') >= 0) throw badRequest("제목에는 줄바꿈을 사용할 수 없습니다.");
        if (r.content() == null || r.content().isBlank()) throw badRequest("내용을 입력해 주세요.");
        if (r.content().length() > 3000) throw badRequest("내용은 3000자 이하로 입력해 주세요.");
    }
    private String headerValue(String value) { return value.replace('\r', ' ').replace('\n', ' '); }
    private ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "문의를 찾을 수 없습니다."); }
    private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
