package com.guildup.community.domain;

import com.guildup.user.domain.User;
import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import java.time.Instant;

/** GuildUp 커뮤니티 자체 이벤트. 외부 연동 없이 커뮤니티와 작성자를 직접 참조한다. */
@Entity
@Table(name = "community_events", indexes = {@Index(name = "idx_community_events_start", columnList = "community_id, start_at, id"),
        @Index(name = "idx_community_events_end", columnList = "community_id, end_at")})
@org.hibernate.annotations.Check(constraints = "end_at >= start_at")
public class CommunityEvent {
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CommunityEventType type;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CommunityEvent() {}

    public CommunityEvent(Community community, User author, String title, String content,
                           CommunityEventType type, Instant startAt, Instant endAt, Instant now) {
        this.community = community;
        this.author = author;
        this.createdAt = now;
        update(title, content, type, startAt, endAt, now);
    }

    public void update(String title, String content, CommunityEventType type, Instant startAt, Instant endAt, Instant now) {
        this.title = title;
        this.content = content;
        this.type = type;
        this.startAt = startAt;
        this.endAt = endAt;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public Community getCommunity() { return community; }
    public User getAuthor() { return author; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public CommunityEventType getType() { return type; }
    public Instant getStartAt() { return startAt; }
    public Instant getEndAt() { return endAt; }

    public CommunityEventStatus statusAt(Instant now) {
        if (now.isBefore(startAt)) return CommunityEventStatus.UPCOMING;
        if (now.isAfter(endAt)) return CommunityEventStatus.ENDED;
        return CommunityEventStatus.ONGOING;
    }
}
