package com.guildup.announcement.domain;

import com.guildup.user.domain.User;
import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

/** 조회/확인한 사용자만 저장한다. 팝업을 보았다는 사실과 상세 읽음은 구분한다. */
@Entity
@Table(name = "platform_announcement_reads",
        uniqueConstraints = @UniqueConstraint(name = "uk_platform_announcement_reads_user", columnNames = {"announcement_id", "user_id"}),
        indexes = @Index(name = "idx_platform_announcement_reads_user", columnList = "user_id, announcement_id"))
public class PlatformAnnouncementRead {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "announcement_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private PlatformAnnouncement announcement;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;
    @Column(name = "read_at")
    private Instant readAt;
    @Column(name = "popup_confirmed_at")
    private Instant popupConfirmedAt;

    protected PlatformAnnouncementRead() {}
    public PlatformAnnouncementRead(PlatformAnnouncement announcement, User user) {
        this.announcement = announcement;
        this.user = user;
    }
    public void markRead(Instant now) { if (readAt == null) readAt = now; }
    public void confirmPopup(Instant now) {
        markRead(now);
        if (popupConfirmedAt == null) popupConfirmedAt = now;
    }
    public Long getAnnouncementId() { return announcement.getId(); }
    public Instant getReadAt() { return readAt; }
    public Instant getPopupConfirmedAt() { return popupConfirmedAt; }
}
