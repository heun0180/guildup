package com.guildup.killcompetition.domain;

import com.guildup.community.domain.Community;
import com.guildup.community.domain.CommunityMember;
import com.guildup.community.domain.CommunityGame;
import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "kill_competitions", indexes = {
        @Index(name = "idx_kill_competition_community_status", columnList = "community_id, status, ends_at"),
        @Index(name = "idx_kill_competition_result_publish", columnList = "status, result_publish_at")
})
public class KillCompetition {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Community community;

    // 기존 운영 데이터의 게임 경계를 backfill하기 전에도 Hibernate가 컬럼을 추가할 수 있어야 한다.
    // 새 킬내기는 생성자에서 항상 게임을 지정하며, 시작 마이그레이션이 단일 게임 커뮤니티를 채운다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "community_game_id")
    private CommunityGame communityGame;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_member_id", nullable = false)
    private CommunityMember createdBy;

    @Column(nullable = false, length = 100)
    private String title;

    @Enumerated(EnumType.STRING) @Column(name = "game_mode", nullable = false, length = 16)
    private KillCompetitionGameMode gameMode;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private KillCompetitionStatus status;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "recruitment_open", nullable = false, columnDefinition = "boolean default true") private boolean recruitmentOpen = true;
    @Column(name = "recruitment_closed_at") private Instant recruitmentClosedAt;
    @Column(name = "last_interim_calculated_at") private Instant lastInterimCalculatedAt;
    @Column(name = "last_interim_match_started_at") private Instant lastInterimMatchStartedAt;
    @Column(name = "interim_calculation_started_at") private Instant interimCalculationStartedAt;
    @Column(name = "finalization_started_at") private Instant finalizationStartedAt;
    @Column(name = "result_requested_at") private Instant resultRequestedAt;
    @Column(name = "result_publish_at") private Instant resultPublishAt;
    @Column(name = "result_last_error", length = 500) private String resultLastError;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    @Version private long version;

    @OneToMany(mappedBy = "competition", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id asc")
    private Set<KillCompetitionParticipant> participants = new LinkedHashSet<>();

    @OneToMany(mappedBy = "competition", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder asc")
    private Set<KillCompetitionTeam> teams = new LinkedHashSet<>();

    protected KillCompetition() {}

    public KillCompetition(Community community, CommunityGame communityGame, CommunityMember createdBy, String title,
                           KillCompetitionGameMode gameMode, Instant endsAt, Instant now) {
        this.community = community;
        this.communityGame = communityGame;
        this.createdBy = createdBy;
        this.title = title;
        this.gameMode = gameMode;
        this.endsAt = endsAt;
        this.status = KillCompetitionStatus.RECRUITING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void closeRecruitment(Instant now) {
        status = KillCompetitionStatus.READY;
        recruitmentClosedAt = now;
        updatedAt = now;
    }

    public void start(Instant now) {
        status = KillCompetitionStatus.IN_PROGRESS;
        startedAt = now;
        participants.stream().filter(KillCompetitionParticipant::isApproved)
                .forEach(participant -> participant.initializeEligibleFrom(now));
        updatedAt = now;
    }

    public void setRecruitmentOpen(boolean open, Instant now) {
        recruitmentOpen = open;
        if (!open) recruitmentClosedAt = now;
        updatedAt = now;
    }

    public void beginInterim(Instant now) { interimCalculationStartedAt = now; updatedAt = now; }
    public void finishInterim(Instant now, Instant latestMatchStartedAt) {
        lastInterimCalculatedAt = now;
        lastInterimMatchStartedAt = latestMatchStartedAt;
        interimCalculationStartedAt = null;
        updatedAt = now;
    }
    public void clearInterimClaim() { interimCalculationStartedAt = null; }
    public void beginFinalization(Instant now) { finalizationStartedAt = now; updatedAt = now; }
    public void clearFinalizationClaim() { finalizationStartedAt = null; }
    public void requestResult(Instant now, Instant publishAt) {
        status = KillCompetitionStatus.RESULT_PENDING;
        recruitmentOpen = false;
        resultRequestedAt = now;
        resultPublishAt = publishAt;
        resultLastError = null;
        updatedAt = now;
    }
    public void recordResultFailure(String message, Instant now) {
        resultLastError = message == null ? "최종 결과 집계에 실패했습니다." : message.substring(0, Math.min(500, message.length()));
        updatedAt = now;
    }
    public void complete(Instant now) {
        status = KillCompetitionStatus.COMPLETED;
        completedAt = now;
        finalizationStartedAt = null;
        updatedAt = now;
    }
    public void cancel(Instant now) { status = KillCompetitionStatus.CANCELLED; updatedAt = now; }
    public void replaceTeams(List<KillCompetitionTeam> newTeams) {
        participants.forEach(p -> p.assignTeam(null));
        teams.clear();
        teams.addAll(newTeams);
    }
    public void addParticipant(KillCompetitionParticipant participant) { participants.add(participant); }
    public void removeParticipant(KillCompetitionParticipant participant) { participants.remove(participant); }

    public Long getId() { return id; }
    public Community getCommunity() { return community; }
    public CommunityGame getCommunityGame() { return communityGame; }
    public CommunityMember getCreatedBy() { return createdBy; }
    public String getTitle() { return title; }
    public KillCompetitionGameMode getGameMode() { return gameMode; }
    public KillCompetitionStatus getStatus() { return status; }
    public Instant getEndsAt() { return endsAt; }
    public Instant getStartedAt() { return startedAt; }
    public boolean isRecruitmentOpen() { return recruitmentOpen; }
    public Instant getRecruitmentClosedAt() { return recruitmentClosedAt; }
    public Instant getLastInterimCalculatedAt() { return lastInterimCalculatedAt; }
    public Instant getLastInterimMatchStartedAt() { return lastInterimMatchStartedAt; }
    public Instant getInterimCalculationStartedAt() { return interimCalculationStartedAt; }
    public Instant getFinalizationStartedAt() { return finalizationStartedAt; }
    public Instant getResultRequestedAt() { return resultRequestedAt; }
    public Instant getResultPublishAt() { return resultPublishAt; }
    public String getResultLastError() { return resultLastError; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public List<KillCompetitionParticipant> getParticipants() { return participants.stream().toList(); }
    public List<KillCompetitionTeam> getTeams() { return teams.stream().toList(); }
}
