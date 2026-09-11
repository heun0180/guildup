package com.guildup.community.service;

import com.guildup.community.domain.CommunityNotice;
import com.guildup.community.dto.CommunityNoticeRequest;
import com.guildup.community.dto.CommunityNoticeResponse;
import com.guildup.community.repository.CommunityNoticeRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Clock;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class CommunityNoticeService {
    private final CommunityAccessService access;
    private final CommunityNoticeRepository repository;
    private final Clock clock;

    public CommunityNoticeService(CommunityAccessService access, CommunityNoticeRepository repository, Clock clock) {
        this.access = access;
        this.repository = repository;
        this.clock = clock;
    }

    public List<CommunityNoticeResponse> list(Long userId, Long communityId) {
        access.requireAccess(userId, communityId);
        return repository.findByCommunityIdOrderByPinnedDescCreatedAtDescIdDesc(communityId).stream()
                .map(item -> CommunityNoticeResponse.from(item)).toList();
    }

    public CommunityNoticeResponse get(Long userId, Long communityId, Long id) {
        access.requireAccess(userId, communityId);
        var item = requireItem(communityId, id);
        return CommunityNoticeResponse.from(item);
    }

    @Transactional
    public CommunityNoticeResponse create(Long userId, Long communityId, CommunityNoticeRequest request) {
        var membership = access.requireManagementAccess(userId, communityId);
        validate(request);
        var item = repository.save(new CommunityNotice(membership.getCommunity(), membership.getUser(),
                CommunityNewsValidation.title(request.title()), CommunityNewsValidation.content(request.content(), true),
                request.important(), request.pinned(), clock.instant()));
        return CommunityNoticeResponse.from(item);
    }

    @Transactional
    public CommunityNoticeResponse update(Long userId, Long communityId, Long id, CommunityNoticeRequest request) {
        access.requireManagementAccess(userId, communityId);
        var item = requireItem(communityId, id);
        validate(request);
        item.update(CommunityNewsValidation.title(request.title()), CommunityNewsValidation.content(request.content(), true),
                request.important(), request.pinned(), clock.instant());
        return CommunityNoticeResponse.from(item);
    }

    @Transactional
    public void delete(Long userId, Long communityId, Long id) {
        access.requireManagementAccess(userId, communityId);
        repository.delete(requireItem(communityId, id));
    }

    private CommunityNotice requireItem(Long communityId, Long id) {
        return repository.findByIdAndCommunityId(id, communityId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "공지를 찾을 수 없습니다."));
    }

    private void validate(CommunityNoticeRequest request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "입력 내용을 확인해 주세요.");
        CommunityNewsValidation.title(request.title());
        CommunityNewsValidation.content(request.content(), true);
    }
}
