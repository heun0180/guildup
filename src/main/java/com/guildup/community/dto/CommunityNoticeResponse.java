package com.guildup.community.dto;

import com.guildup.community.domain.*;
import java.time.Instant;

public record CommunityNoticeResponse(Long id, Long communityId, Long authorId, String authorName,
                                      String title, String content, boolean important, boolean pinned,
                                      Instant createdAt, Instant updatedAt) {
    public static CommunityNoticeResponse from(CommunityNotice item) {
        return new CommunityNoticeResponse(item.getId(), item.getCommunity().getId(),
                item.getAuthor().getId(), item.getAuthor().getNickname(), item.getTitle(), item.getContent(),
                item.isImportant(), item.isPinned(), item.getCreatedAt(), item.getUpdatedAt());
    }
}
