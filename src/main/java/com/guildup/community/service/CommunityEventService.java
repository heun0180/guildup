package com.guildup.community.service;

import com.guildup.community.domain.CommunityEvent;
import com.guildup.community.dto.CommunityEventRequest;
import com.guildup.community.dto.CommunityEventResponse;
import com.guildup.community.repository.CommunityEventRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Clock;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class CommunityEventService {
    private final CommunityAccessService access;
    private final CommunityEventRepository repository;
    private final Clock clock;

    public CommunityEventService(CommunityAccessService access, CommunityEventRepository repository, Clock clock) {
        this.access = access;
        this.repository = repository;
        this.clock = clock;
    }

    public List<CommunityEventResponse> list(Long userId, Long communityId) {
        access.requireAccess(userId, communityId);
        var now = clock.instant();
        return repository.findFeed(communityId, now).stream()
                .map(item -> CommunityEventResponse.from(item, now)).toList();
    }

    public CommunityEventResponse get(Long userId, Long communityId, Long id) {
        access.requireAccess(userId, communityId);
        var item = requireItem(communityId, id);
        return CommunityEventResponse.from(item, clock.instant());
    }

    @Transactional
    public CommunityEventResponse create(Long userId, Long communityId, CommunityEventRequest request) {
        var membership = access.requireManagementAccess(userId, communityId);
        validate(request);
        var item = repository.save(new CommunityEvent(membership.getCommunity(), membership.getUser(),
                CommunityNewsValidation.title(request.title()), CommunityNewsValidation.content(request.content(), false),
                request.type(), request.startAt(), request.endAt(), clock.instant()));
        return CommunityEventResponse.from(item, clock.instant());
    }

    @Transactional
    public CommunityEventResponse update(Long userId, Long communityId, Long id, CommunityEventRequest request) {
        access.requireManagementAccess(userId, communityId);
        var item = requireItem(communityId, id);
        validate(request);
        item.update(CommunityNewsValidation.title(request.title()), CommunityNewsValidation.content(request.content(), false),
                request.type(), request.startAt(), request.endAt(), clock.instant());
        return CommunityEventResponse.from(item, clock.instant());
    }

    @Transactional
    public void delete(Long userId, Long communityId, Long id) {
        access.requireManagementAccess(userId, communityId);
        repository.delete(requireItem(communityId, id));
    }

    private CommunityEvent requireItem(Long communityId, Long id) {
        return repository.findByIdAndCommunityId(id, communityId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "이벤트를 찾을 수 없습니다."));
    }

    private void validate(CommunityEventRequest request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "입력 내용을 확인해 주세요.");
        CommunityNewsValidation.title(request.title());
        CommunityNewsValidation.content(request.content(), false);
        if (request.type() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이벤트 종류를 선택해 주세요.");
        }
        if (request.startAt() == null || request.endAt() == null || request.endAt().isBefore(request.startAt())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "시작일과 종료일을 입력하고 종료일을 시작일 이후로 설정해 주세요.");
        }
    }
}
