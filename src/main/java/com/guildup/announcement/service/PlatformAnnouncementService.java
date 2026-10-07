package com.guildup.announcement.service;

import com.guildup.announcement.domain.*;
import com.guildup.announcement.dto.PlatformAnnouncementRequest;
import com.guildup.announcement.dto.PlatformAnnouncementResponses.*;
import com.guildup.announcement.repository.*;
import com.guildup.developer.config.DeveloperAccessService;
import com.guildup.user.domain.User;
import com.guildup.user.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class PlatformAnnouncementService {
    private final PlatformAnnouncementRepository announcements;
    private final PlatformAnnouncementReadRepository reads;
    private final UserRepository users;
    private final DeveloperAccessService access;
    private final Clock clock;

    public PlatformAnnouncementService(PlatformAnnouncementRepository announcements, PlatformAnnouncementReadRepository reads,
                                       UserRepository users, DeveloperAccessService access, Clock clock) {
        this.announcements = announcements;
        this.reads = reads;
        this.users = users;
        this.access = access;
        this.clock = clock;
    }

    public Page list(Long userId, int page, int size) {
        requireUser(userId);
        Instant now = clock.instant();
        var result = announcements.findVisible(now, pagination(page, size));
        return new Page(items(result.getContent(), userId, now), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    public Detail get(Long userId, Long id) {
        requireUser(userId);
        Instant now = clock.instant();
        return detail(requireVisible(id, now), reads.findByAnnouncement_IdAndUser_Id(id, userId).orElse(null), now);
    }

    public Notifications notifications(Long userId) {
        requireUser(userId);
        Instant now = clock.instant();
        return new Notifications(announcements.countUnread(userId, now),
                items(announcements.findRecent(now, PageRequest.of(0, 5)), userId, now));
    }

    public Detail popup(Long userId) {
        requireUser(userId);
        Instant now = clock.instant();
        return announcements.findPendingPopups(userId, now, PageRequest.of(0, 1)).stream().findFirst()
                .map(a -> detail(a, reads.findByAnnouncement_IdAndUser_Id(a.getId(), userId).orElse(null), now))
                .orElse(null);
    }

    /** 기존 UserRepository의 사용자 잠금으로 여러 탭의 동시 최초 읽음을 직렬화한다. */
    @Transactional
    public Detail markRead(Long userId, Long id, boolean confirmPopup) {
        User user = users.findForUpdate(userId).filter(com.guildup.user.domain.User::isActive).orElseThrow(() -> unauthorized());
        Instant now = clock.instant();
        var a = requireVisible(id, now);
        if (confirmPopup && (!a.isImportant() || !a.isPopup())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "팝업 공지가 아닙니다.");
        }
        var r = reads.findByAnnouncement_IdAndUser_Id(id, userId)
                .orElseGet(() -> new PlatformAnnouncementRead(a, user));
        if (confirmPopup) r.confirmPopup(now); else r.markRead(now);
        reads.save(r);
        return detail(a, r, now);
    }

    public Page adminList(Long userId, int page, int size) {
        access.requireSystemAdmin(userId);
        Instant now = clock.instant();
        var paging = pagination(page, size);
        var result = announcements.findAll(PageRequest.of(paging.getPageNumber(), paging.getPageSize(),
                Sort.by(Sort.Direction.DESC, "pinned", "important", "createdAt", "id")));
        return new Page(items(result.getContent(), userId, now), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    public Detail adminGet(Long userId, Long id) {
        access.requireSystemAdmin(userId);
        return detail(requireItem(id), null, clock.instant());
    }

    @Transactional
    public Detail create(Long userId, PlatformAnnouncementRequest request) {
        access.requireSystemAdmin(userId);
        validate(request);
        Instant now = clock.instant();
        var a = announcements.save(new PlatformAnnouncement(requireUser(userId), request.title().strip(),
                request.content().strip(), request.type(), request.important(), request.pinned(), request.popup(),
                request.published(), request.publishStartAt(), request.publishEndAt(), now));
        return detail(a, null, now);
    }

    @Transactional
    public Detail update(Long userId, Long id, PlatformAnnouncementRequest request) {
        access.requireSystemAdmin(userId);
        var a = requireItem(id);
        validate(request);
        Instant now = clock.instant();
        a.update(request.title().strip(), request.content().strip(), request.type(), request.important(), request.pinned(),
                request.popup(), request.published(), request.publishStartAt(), request.publishEndAt(), now);
        return detail(a, null, now);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        access.requireSystemAdmin(userId);
        announcements.delete(requireItem(id));
    }

    private List<Item> items(List<PlatformAnnouncement> list, Long userId, Instant now) {
        if (list.isEmpty()) return List.of();
        Map<Long, PlatformAnnouncementRead> states = reads.findByUser_IdAndAnnouncement_IdIn(userId,
                        list.stream().map(PlatformAnnouncement::getId).toList()).stream()
                .collect(Collectors.toMap(PlatformAnnouncementRead::getAnnouncementId, Function.identity()));
        return list.stream().map(a -> Item.from(a, states.get(a.getId()), now)).toList();
    }
    private Detail detail(PlatformAnnouncement a, PlatformAnnouncementRead r, Instant now) {
        return new Detail(Item.from(a, r, now), a.getContent());
    }
    private PlatformAnnouncement requireVisible(Long id, Instant now) {
        return announcements.findVisibleById(id, now).orElseThrow(() -> notFound());
    }
    private PlatformAnnouncement requireItem(Long id) {
        return announcements.findById(id).orElseThrow(() -> notFound());
    }
    private User requireUser(Long id) { return users.findById(id).filter(com.guildup.user.domain.User::isActive).orElseThrow(() -> unauthorized()); }
    private ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
    }
    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "공지를 찾을 수 없습니다.");
    }
    private PageRequest pagination(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "페이지와 조회 개수를 확인해 주세요. (1~100)");
        }
        return PageRequest.of(page, size);
    }
    private void validate(PlatformAnnouncementRequest r) {
        if (r == null || r.title() == null || r.title().isBlank() || r.title().length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "제목은 1~200자로 입력해 주세요.");
        }
        if (r.content() == null || r.content().isBlank() || r.content().length() > 20000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "내용은 1~20,000자로 입력해 주세요.");
        }
        if (r.type() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "공지 유형을 선택해 주세요.");
        if (r.publishStartAt() != null && r.publishEndAt() != null && !r.publishEndAt().isAfter(r.publishStartAt())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "게시 종료는 시작보다 이후여야 합니다.");
        }
        if (r.popup() && !r.important()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "중요 공지만 팝업으로 노출할 수 있습니다.");
        }
    }
}
