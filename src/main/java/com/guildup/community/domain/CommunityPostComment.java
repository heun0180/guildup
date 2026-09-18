package com.guildup.community.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

@Entity
@Table(name = "community_post_comments", indexes = {
        @Index(name = "idx_community_post_comments_post", columnList = "post_id, created_at, id")
})
public class CommunityPostComment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private CommunityPost post;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_community_user_id", nullable = false)
    private CommunityUser authorCommunityUser;

    @Column(nullable = false, length = 2000)
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected CommunityPostComment() {}

    public CommunityPostComment(CommunityPost post, CommunityUser authorCommunityUser, String content, Instant now) {
        this.post = post;
        this.authorCommunityUser = authorCommunityUser;
        this.createdAt = now;
        update(content, now);
    }

    public void update(String content, Instant now) {
        this.content = content;
        this.updatedAt = now;
    }

    public void delete(Instant now) { this.deletedAt = now; }

    public Long getId() { return id; }
    public CommunityPost getPost() { return post; }
    public CommunityUser getAuthorCommunityUser() { return authorCommunityUser; }
    public String getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getDeletedAt() { return deletedAt; }
}
