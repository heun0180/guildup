package com.guildup.community.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;
import java.time.LocalDate;

/** Asia/Seoul 기준 하루 한 번의 커뮤니티 멤버 출석 기록이다. */
@Entity
@Table(
        name = "community_attendances",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_community_attendance_member_date",
                columnNames = {"community_member_id", "attendance_date"}
        ),
        indexes = @Index(
                name = "idx_community_attendance_community_date",
                columnList = "community_id, attendance_date"
        )
)
public class CommunityAttendance {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Community community;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_member_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private CommunityMember communityMember;

    @Column(name = "attendance_date", nullable = false)
    private LocalDate attendanceDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CommunityAttendance() {}

    public CommunityAttendance(CommunityMember communityMember, LocalDate attendanceDate, Instant createdAt) {
        this.communityMember = communityMember;
        this.community = communityMember.getCommunity();
        this.attendanceDate = attendanceDate;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Community getCommunity() { return community; }
    public CommunityMember getCommunityMember() { return communityMember; }
    public LocalDate getAttendanceDate() { return attendanceDate; }
    public Instant getCreatedAt() { return createdAt; }
}
