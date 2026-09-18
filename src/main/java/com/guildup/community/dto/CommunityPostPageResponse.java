package com.guildup.community.dto;

import org.springframework.data.domain.Page;

import java.util.List;

public record CommunityPostPageResponse(
        List<CommunityPostListItemResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
    public static CommunityPostPageResponse from(Page<?> page, List<CommunityPostListItemResponse> content) {
        return new CommunityPostPageResponse(content, page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages(), page.isFirst(), page.isLast());
    }
}
