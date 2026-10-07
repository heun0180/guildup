package com.guildup.feedback.dto;

import com.guildup.feedback.domain.*;
import java.time.Instant;
import java.util.List;

public final class FeedbackResponses {
    private FeedbackResponses() {}
    public record Item(Long id, FeedbackType type, String title, Long userId, String authorNickname,
                       Instant createdAt, FeedbackStatus status, Long communityId, String communityName) {
        public static Item from(Feedback f) {
            return new Item(f.getId(), f.getType(), f.getTitle(), f.getUser().getId(), f.getAuthorNickname(),
                    f.getCreatedAt(), f.getStatus(), f.getCommunityId(), f.getCommunityName());
        }
    }
    public record Detail(Item feedback, String content, String pageRoute, String answer, Instant answeredAt,
                         Instant updatedAt, long version) {
        public static Detail from(Feedback f) {
            return new Detail(Item.from(f), f.getContent(), f.getPageRoute(), f.getAnswer(), f.getAnsweredAt(),
                    f.getUpdatedAt(), f.getVersion());
        }
    }
    public record Page(List<Item> content, int page, int size, long totalElements, int totalPages) {}
}
