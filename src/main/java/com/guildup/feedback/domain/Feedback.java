package com.guildup.feedback.domain;

import com.guildup.user.domain.User;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "feedbacks", indexes = {
        @Index(name = "idx_feedbacks_user_created", columnList = "user_id,created_at,id"),
        @Index(name = "idx_feedbacks_created", columnList = "created_at,id")
})
public class Feedback {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;
    @Column(name = "author_nickname", nullable = false, updatable = false)
    private String authorNickname;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private FeedbackType type;
    @Column(nullable = false, length = 100)
    private String title;
    @Column(nullable = false, length = 3000)
    private String content;
    // 접수 당시 선택적 context. 커뮤니티 삭제 후에도 ID/이름을 보존한다.
    @Column(name = "community_id", updatable = false)
    private Long communityId;
    @Column(name = "community_name", updatable = false)
    private String communityName;
    @Column(name = "page_route", length = 300, updatable = false)
    private String pageRoute;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private FeedbackStatus status = FeedbackStatus.RECEIVED;
    @Column(length = 3000)
    private String answer;
    @Column(name = "answered_at")
    private Instant answeredAt;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "answered_by_user_id")
    private User answeredBy;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected Feedback() {}
    public Feedback(User user, FeedbackType type, String title, String content,
                    Long communityId, String communityName, String pageRoute, Instant now) {
        this.user = user;
        this.authorNickname = user.getNickname();
        this.type = type;
        this.title = title;
        this.content = content;
        this.communityId = communityId;
        this.communityName = communityName;
        this.pageRoute = pageRoute;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void manage(FeedbackStatus status, String answer, User admin, Instant now) {
        if (!java.util.Objects.equals(this.answer, answer)) {
            this.answer = answer;
            this.answeredAt = answer == null ? null : now;
            this.answeredBy = answer == null ? null : admin;
        }
        this.status = status;
        this.updatedAt = now;
    }
    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getAuthorNickname() { return user.isActive() ? authorNickname : User.WITHDRAWN_NAME; }
    public FeedbackType getType() { return type; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public Long getCommunityId() { return communityId; }
    public String getCommunityName() { return communityName; }
    public String getPageRoute() { return pageRoute; }
    public FeedbackStatus getStatus() { return status; }
    public String getAnswer() { return answer; }
    public Instant getAnsweredAt() { return answeredAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
