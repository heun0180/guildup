package com.guildup.community.dto;

import com.guildup.community.domain.CommunityPost;
import com.guildup.community.domain.CommunityPostCategory;
import com.guildup.community.domain.CommunityUser;
import com.guildup.community.domain.CommunityUserRole;

import java.time.Instant;
import java.util.List;

public record CommunityPostDetailResponse(
        Long id,
        Long communityId,
        CommunityPostCategory category,
        String title,
        String content,
        Long authorCommunityUserId,
        String authorName,
        CommunityUserRole authorRole,
        boolean notice,
        boolean pinned,
        long viewCount,
        Instant createdAt,
        Instant updatedAt,
        boolean edited,
        boolean canEdit,
        boolean canDelete,
        boolean canManage,
        List<CommunityPostCommentResponse> comments
) {
    public static CommunityPostDetailResponse from(CommunityPost post, CommunityUser viewer,
                                                    List<CommunityPostCommentResponse> comments) {
        CommunityUser author = post.getAuthorCommunityUser();
        boolean mine = author.getId().equals(viewer.getId());
        boolean manager = viewer.getRole() == CommunityUserRole.OWNER || viewer.getRole() == CommunityUserRole.ADMIN;
        return new CommunityPostDetailResponse(
                post.getId(), post.getCommunity().getId(), post.getCategory(), post.getTitle(), post.getContent(),
                author.getId(), author.getUser().getNickname(), author.getRole(), post.isNotice(), post.isPinned(),
                post.getViewCount(), post.getCreatedAt(), post.getUpdatedAt(),
                post.getUpdatedAt().isAfter(post.getCreatedAt()), mine, mine || manager, manager, comments
        );
    }
}
