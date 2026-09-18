package com.guildup.community.dto;

import com.guildup.community.domain.CommunityPostComment;
import com.guildup.community.domain.CommunityUser;
import com.guildup.community.domain.CommunityUserRole;

import java.time.Instant;

public record CommunityPostCommentResponse(
        Long id,
        Long authorCommunityUserId,
        String authorName,
        CommunityUserRole authorRole,
        String content,
        Instant createdAt,
        Instant updatedAt,
        boolean edited,
        boolean canEdit,
        boolean canDelete
) {
    public static CommunityPostCommentResponse from(CommunityPostComment comment, CommunityUser viewer) {
        CommunityUser author = comment.getAuthorCommunityUser();
        boolean mine = author.getId().equals(viewer.getId());
        boolean manager = viewer.getRole() == CommunityUserRole.OWNER || viewer.getRole() == CommunityUserRole.ADMIN;
        return new CommunityPostCommentResponse(
                comment.getId(), author.getId(), author.getUser().getNickname(), author.getRole(),
                comment.getContent(), comment.getCreatedAt(), comment.getUpdatedAt(),
                comment.getUpdatedAt().isAfter(comment.getCreatedAt()), mine, mine || manager
        );
    }
}
