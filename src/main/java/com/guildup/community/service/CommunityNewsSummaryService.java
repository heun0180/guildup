package com.guildup.community.service;

import com.guildup.community.dto.*;
import com.guildup.community.repository.CommunityNoticeRepository;
import com.guildup.community.repository.CommunityEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;

@Service
@Transactional(readOnly = true)
public class CommunityNewsSummaryService {
    private final CommunityAccessService access;
    private final CommunityNoticeRepository notices;
    private final CommunityEventRepository events;
    private final Clock clock;

    public CommunityNewsSummaryService(CommunityAccessService access, CommunityNoticeRepository notices,
                                        CommunityEventRepository events, Clock clock) {
        this.access = access;
        this.notices = notices;
        this.events = events;
        this.clock = clock;
    }

    public CommunityNewsSummaryResponse get(Long userId, Long communityId) {
        access.requireAccess(userId, communityId);
        var now = clock.instant();
        return new CommunityNewsSummaryResponse(
                notices.findTop3ByCommunityIdOrderByPinnedDescCreatedAtDescIdDesc(communityId).stream()
                        .map(CommunityNoticeResponse::from).toList(),
                events.findTop3ByCommunityIdAndStartAtGreaterThanEqualOrderByStartAtAscIdAsc(communityId, now)
                        .stream().map(item -> CommunityEventResponse.from(item, now)).toList());
    }
}
