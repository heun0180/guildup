package com.guildup.community.domain;

import com.guildup.user.domain.User;
import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import java.time.Instant;

/** GuildUp 커뮤니티 자체 공지. 외부 연동 없이 커뮤니티와 작성자를 직접 참조한다. */
@Entity
@Table(name = "community_notices", indexes = {@Index(name = "idx_community_notices_feed", columnList = "community_id, is_pinned DESC, created_at DESC, id DESC")})
public class CommunityNotice {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Community community;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "is_important", nullable = false)
    private boolean important;

    @Column(name = "is_pinned", nullable = false)
    private boolean pinned;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CommunityNotice() {}

    public CommunityNotice(Community community, User author, String title, String content,
                           boolean important, boolean pinned, Instant now) {
        this.community = community;
        this.author = author;
        this.createdAt = now;
        update(title, content, important, pinned, now);
    }

    public void update(String title, String content, boolean important, boolean pinned, Instant now) {
        this.title = title;
        this.content = content;
        this.important = important;
        this.pinned = pinned;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public Community getCommunity() { return community; }
    public User getAuthor() { return author; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public boolean isImportant() { return important; }
    public boolean isPinned() { return pinned; }
}
