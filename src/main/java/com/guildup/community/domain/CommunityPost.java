package com.guildup.community.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

@Entity
@Table(name = "community_posts", indexes = {
        @Index(name = "idx_community_posts_feed", columnList = "community_id, is_pinned, is_notice, created_at, id")
})
public class CommunityPost {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Community community;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_community_user_id", nullable = false)
    private CommunityUser authorCommunityUser;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CommunityPostCategory category;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "is_notice", nullable = false)
    private boolean notice;

    @Column(name = "is_pinned", nullable = false)
    private boolean pinned;

    @Column(name = "view_count", nullable = false)
    private long viewCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected CommunityPost() {}

    public CommunityPost(Community community, CommunityUser authorCommunityUser,
                         CommunityPostCategory category, String title, String content,
                         boolean notice, boolean pinned, Instant now) {
        this.community = community;
        this.authorCommunityUser = authorCommunityUser;
        this.createdAt = now;
        this.viewCount = 0L;
        updateContent(category, title, content, now);
        updateManagement(notice, pinned, now);
    }

    public void updateContent(CommunityPostCategory category, String title, String content, Instant now) {
        this.category = category;
        this.title = title;
        this.content = content;
        this.updatedAt = now;
    }

    public void updateManagement(boolean notice, boolean pinned, Instant now) {
        this.notice = notice;
        this.pinned = pinned;
        this.updatedAt = now;
    }

    public void delete(Instant now) { this.deletedAt = now; }
    public Long getId() { return id; }
    public Community getCommunity() { return community; }
    public CommunityUser getAuthorCommunityUser() { return authorCommunityUser; }
    public CommunityPostCategory getCategory() { return category; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public boolean isNotice() { return notice; }
    public boolean isPinned() { return pinned; }
    public long getViewCount() { return viewCount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getDeletedAt() { return deletedAt; }
}
