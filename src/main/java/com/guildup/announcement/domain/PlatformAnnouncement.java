package com.guildup.announcement.domain;

import com.guildup.community.domain.Community;
import com.guildup.community.domain.GameType;
import com.guildup.user.domain.User;
import jakarta.persistence.*;

import java.time.Duration;
import java.time.Instant;

/** GuildUp 전체 서비스 공지. CommunityNotice와 데이터/관리 권한을 공유하지 않는다. */
@Entity
@Table(name = "platform_announcements", indexes = {
        @Index(name = "idx_platform_announcements_feed", columnList = "target_type, is_published, is_pinned DESC, is_important DESC, created_at DESC, id DESC")
})
public class PlatformAnnouncement {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User author;

    @Column(nullable = false, length = 200)
    private String title;
    @Column(nullable = false, columnDefinition = "text")
    private String content;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private PlatformAnnouncementType type;
    @Column(name = "is_important", nullable = false)
    private boolean important;
    @Column(name = "is_pinned", nullable = false)
    private boolean pinned;
    @Column(name = "is_popup", nullable = false)
    private boolean popup;
    @Column(name = "is_published", nullable = false)
    private boolean published;
    @Column(name = "publish_start_at")
    private Instant publishStartAt;
    @Column(name = "publish_end_at")
    private Instant publishEndAt;
    @Column(name = "published_at")
    private Instant publishedAt;

    @Enumerated(EnumType.STRING) @Column(name = "target_type", nullable = false, length = 30)
    private PlatformAnnouncementTarget targetType = PlatformAnnouncementTarget.ALL;
    @Enumerated(EnumType.STRING) @Column(name = "target_game_type", length = 50)
    private GameType targetGameType;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "target_community_id")
    private Community targetCommunity;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PlatformAnnouncement() {}

    public PlatformAnnouncement(User author, String title, String content, PlatformAnnouncementType type,
                                boolean important, boolean pinned, boolean popup, boolean published,
                                Instant start, Instant end, Instant now) {
        this.author = author;
        this.createdAt = now;
        update(title, content, type, important, pinned, popup, published, start, end, now);
    }

    public void update(String title, String content, PlatformAnnouncementType type, boolean important,
                       boolean pinned, boolean popup, boolean published, Instant start, Instant end, Instant now) {
        this.title = title;
        this.content = content;
        this.type = type;
        this.important = important;
        this.pinned = pinned;
        this.popup = popup;
        this.published = published;
        this.publishStartAt = start;
        this.publishEndAt = end;
        if (published && publishedAt == null) publishedAt = now;
        this.updatedAt = now;
    }

    public String status(Instant now) {
        if (!published) return "PRIVATE";
        if (publishEndAt != null && !now.isBefore(publishEndAt)) return "ENDED";
        if (publishStartAt != null && now.isBefore(publishStartAt)) return "SCHEDULED";
        return "PUBLISHED";
    }

    public boolean isNew(Instant now) {
        if (!"PUBLISHED".equals(status(now)) || publishedAt == null) return false;
        Instant visibleSince = publishStartAt != null && publishStartAt.isAfter(publishedAt)
                ? publishStartAt : publishedAt;
        return !now.isBefore(visibleSince) && now.isBefore(visibleSince.plus(Duration.ofHours(24)));
    }

    public Long getId() { return id; }
    public User getAuthor() { return author; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public PlatformAnnouncementType getType() { return type; }
    public boolean isImportant() { return important; }
    public boolean isPinned() { return pinned; }
    public boolean isPopup() { return popup; }
    public boolean isPublished() { return published; }
    public Instant getPublishStartAt() { return publishStartAt; }
    public Instant getPublishEndAt() { return publishEndAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public PlatformAnnouncementTarget getTargetType() { return targetType; }
}
