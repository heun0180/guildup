package com.guildup.community.dto;

import com.guildup.community.domain.*;
import java.time.Instant;

public record CommunityEventResponse(Long id, Long communityId, Long authorId, String authorName,
                                      String title, String content, CommunityEventType type, Instant startAt, Instant endAt, CommunityEventStatus status,
                                      Instant createdAt, Instant updatedAt) {
    public static CommunityEventResponse from(CommunityEvent item, Instant now) {
        return new CommunityEventResponse(item.getId(), item.getCommunity().getId(),
                item.getAuthor().getId(), item.getAuthor().getNickname(), item.getTitle(), item.getContent(),
                item.getType(), item.getStartAt(), item.getEndAt(), item.statusAt(now), item.getCreatedAt(), item.getUpdatedAt());
    }
}
