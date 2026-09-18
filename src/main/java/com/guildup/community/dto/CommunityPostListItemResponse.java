package com.guildup.community.dto;

import com.guildup.community.domain.CommunityPost;
import com.guildup.community.domain.CommunityPostCategory;
import com.guildup.community.domain.CommunityUser;
import com.guildup.community.domain.CommunityUserRole;

import java.time.Instant;

public record CommunityPostListItemResponse(
        Long id,
        CommunityPostCategory category,
        String title,
        Long authorCommunityUserId,
        String authorName,
        CommunityUserRole authorRole,
        boolean notice,
        boolean pinned,
        long viewCount,
        long commentCount,
        Instant createdAt,
        Instant updatedAt
) {
    public static CommunityPostListItemResponse from(CommunityPost post, long commentCount) {
        CommunityUser author = post.getAuthorCommunityUser();
        return new CommunityPostListItemResponse(
                post.getId(), post.getCategory(), post.getTitle(), author.getId(),
                author.getUser().getNickname(), author.getRole(), post.isNotice(), post.isPinned(),
                post.getViewCount(), commentCount, post.getCreatedAt(), post.getUpdatedAt()
        );
    }
}
