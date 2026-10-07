package com.guildup.announcement.dto;

import com.guildup.announcement.domain.*;
import java.time.Instant;
import java.util.List;

public final class PlatformAnnouncementResponses {
    private PlatformAnnouncementResponses() {}

    public record Item(Long id, String title, PlatformAnnouncementType type, boolean important, boolean pinned,
                       boolean popup, boolean published, Instant publishStartAt, Instant publishEndAt,
                       Instant publishedAt, Instant createdAt, Instant updatedAt, String status,
                       boolean newAnnouncement, boolean read, boolean popupConfirmed, PlatformAnnouncementTarget targetType) {
        public static Item from(PlatformAnnouncement a, PlatformAnnouncementRead r, Instant now) {
            return new Item(a.getId(), a.getTitle(), a.getType(), a.isImportant(), a.isPinned(), a.isPopup(),
                    a.isPublished(), a.getPublishStartAt(), a.getPublishEndAt(), a.getPublishedAt(), a.getCreatedAt(),
                    a.getUpdatedAt(), a.status(now), a.isNew(now), r != null && r.getReadAt() != null,
                    r != null && r.getPopupConfirmedAt() != null, a.getTargetType());
        }
    }
    public record Detail(Item announcement, String content) {}
    public record Page(List<Item> content, int page, int size, long totalElements, int totalPages) {}
    public record Notifications(long unreadCount, List<Item> recent) {}
}
