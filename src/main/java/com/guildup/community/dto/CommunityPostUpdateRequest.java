package com.guildup.community.dto;

import com.guildup.community.domain.CommunityPostCategory;

public record CommunityPostUpdateRequest(
        CommunityPostCategory category,
        String title,
        String content,
        Boolean notice,
        Boolean pinned
) {}
